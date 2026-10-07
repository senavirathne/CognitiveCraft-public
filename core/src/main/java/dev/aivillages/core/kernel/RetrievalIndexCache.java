package dev.aivillages.core.kernel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static dev.aivillages.core.kernel.CapabilityRetrievalIndex.*;
import static dev.aivillages.core.kernel.Contracts.*;
import dev.aivillages.core.kernel.VersionedSkillRepository.Candidate;
import dev.aivillages.core.kernel.VersionedSkillRepository.Integrity;

/** Disposable schema-1 binary metadata cache. All methods run on the rebuild worker. */
final class RetrievalIndexCache {
    static final int MAGIC = 0x43435249;
    static final int SCHEMA = 1;
    static final String FILE = "metadata.bin", PENDING = "metadata.pending.bin";
    private RetrievalIndexCache() { }

    record Header(Identity identity, Epoch epoch, String registry, UUID generation, int entries) { }
    static final class UnknownFormat extends IOException {
        UnknownFormat() { super("Unknown retrieval cache format remains inactive"); }
    }
    static final class LimitReached extends IOException {
        LimitReached() { super("Retrieval cache byte limit"); }
    }

    static byte[] encode(Metadata row) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            Candidate c = row.candidate();
            text(out, c.ref().capability().name()); out.writeInt(c.ref().capability().version());
            text(out, c.ref().sha256()); out.writeInt(c.admission().ordinal());
            out.writeInt(c.compatibility().status().ordinal());
            out.writeInt(c.compatibility().reasons().size());
            for (var reason : c.compatibility().reasons()) out.writeInt(reason.ordinal());
            text(out, c.compatibility().runtimeFingerprint()); out.writeInt(c.integrity().ordinal());
            out.writeInt(row.tags().size());
            for (String tag : row.tags().stream().sorted().toList()) text(out, tag);
        }
        return bytes.toByteArray();
    }

    static Metadata decode(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            ArtifactRef ref = new ArtifactRef(new CapabilityId(text(in), in.readInt()), text(in));
            AdmissionStatus admission = item(AdmissionStatus.values(), in.readInt());
            CompatibilityStatus status = item(CompatibilityStatus.values(), in.readInt());
            int reasonCount = count(in.readInt(), 16);
            var reasons = new ArrayList<Outcomes.Reason>();
            for (int i=0;i<reasonCount;i++) reasons.add(item(Outcomes.Reason.values(), in.readInt()));
            Compatibility compatible = new Compatibility(ref, status, reasons, text(in));
            Integrity integrity = item(Integrity.values(), in.readInt());
            int tagCount = count(in.readInt(), 8);
            var tags = new HashSet<String>();
            for (int i=0;i<tagCount;i++) if (!tags.add(text(in))) throw new IOException("Duplicate tag");
            if (in.read()!=-1) throw new IOException("Trailing metadata bytes");
            return new Metadata(new Candidate(ref, admission, compatible, integrity), tags);
        } catch (IllegalArgumentException malformed) { throw new IOException("Invalid cached metadata", malformed); }
    }

    static final class Reader implements AutoCloseable {
        final Header header;
        final long bytes;
        private final MessageDigest digest = sha256();
        private final DigestInputStream stream;
        private final DataInputStream in;
        private final Limits limits;
        private int read;
        Reader(Path root, Identity identity, Epoch epoch, String registry, Limits limits) throws IOException {
            this.limits=limits;
            Path file=root.resolve(FILE); regular(file);
            bytes=Files.size(file);
            if (bytes<32 || bytes>limits.cacheBytes()) throw new LimitReached();
            stream=new DigestInputStream(Files.newInputStream(file),digest); in=new DataInputStream(stream);
            try {
                if (in.readInt()!=MAGIC || in.readInt()!=SCHEMA) throw new UnknownFormat();
                Identity stored=new Identity(uuid(in),uuid(in));
                Epoch stamp=new Epoch(in.readLong(),in.readLong(),in.readLong(),in.readBoolean());
                String registered=text(in); UUID generation=uuid(in); int entries=count(in.readInt(),limits.entries());
                if (!stored.equals(identity) || !stamp.equals(epoch) || !registered.equals(registry))
                    throw new IOException("Retrieval cache context changed");
                header=new Header(stored,stamp,registered,generation,entries);
            } catch (IOException | RuntimeException failure) { in.close(); throw failure; }
        }
        List<Metadata> next(int maximum) throws IOException {
            if (maximum<1 || maximum>limits.rebuildSlice()) throw new IllegalArgumentException("Cache read slice");
            var rows=new ArrayList<Metadata>();
            while (rows.size()<maximum && read<header.entries()) {
                int length=count(in.readInt(),limits.entryBytes());
                if (length==0) throw new IOException("Empty metadata");
                byte[] data=in.readNBytes(length);
                if (data.length!=length) throw new EOFException();
                rows.add(decode(data)); read++;
            }
            return List.copyOf(rows);
        }
        boolean done() { return read==header.entries(); }
        void verify() throws IOException {
            if (!done()) throw new IOException("Incomplete metadata generation");
            stream.on(false); byte[] expected=digest.digest(); byte[] actual=in.readNBytes(32);
            if (!MessageDigest.isEqual(expected,actual) || in.read()!=-1)
                throw new IOException("Retrieval cache checksum/trailing bytes");
        }
        @Override public void close() throws IOException { in.close(); }
    }

    static final class Writer implements AutoCloseable {
        private final Path file,pending;
        private final Limits limits;
        private final long existing;
        private final MessageDigest digest=sha256();
        private final DataOutputStream out;
        private boolean closed,moved;
        private long bytes;
        Writer(Path root,Header header,Limits limits) throws IOException {
            this.limits=limits;
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw new IOException("Symbolic cache directory");
            file=root.resolve(FILE); pending=root.resolve(PENDING);
            if (Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                regular(file); existing=Files.size(file);
                if (existing>limits.cacheBytes()) throw new LimitReached();
                try (var check=new DataInputStream(Files.newInputStream(file))) {
                    if (existing>=8 && (check.readInt()!=MAGIC || check.readInt()!=SCHEMA))
                        throw new UnknownFormat();
                }
            } else existing=0;
            if (Files.exists(pending,LinkOption.NOFOLLOW_LINKS)) {
                regular(pending);
                if (Files.size(pending)>=8) {
                    try (var check=new DataInputStream(Files.newInputStream(pending))) {
                        if (check.readInt()!=MAGIC || check.readInt()!=SCHEMA) throw new UnknownFormat();
                    }
                }
                Files.delete(pending);
            }
            out=new DataOutputStream(new DigestOutputStream(Files.newOutputStream(pending,
                    StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),digest));
            try {
                var encoded=new ByteArrayOutputStream();
                try (var head=new DataOutputStream(encoded)) {
                    head.writeInt(MAGIC); head.writeInt(SCHEMA);
                    uuid(head,header.identity().world()); uuid(head,header.identity().instance());
                    head.writeLong(header.epoch().catalog()); head.writeLong(header.epoch().runtime());
                    head.writeLong(header.epoch().visibility()); head.writeBoolean(header.epoch().available());
                    text(head,header.registry()); uuid(head,header.generation()); head.writeInt(header.entries());
                }
                reserve(encoded.size()); out.write(encoded.toByteArray());
            } catch (IOException | RuntimeException failure) { close(); throw failure; }
        }
        void row(Metadata metadata) throws IOException {
            byte[] data=encode(metadata);
            if (data.length>limits.entryBytes()) throw new LimitReached();
            reserve(4L+data.length); out.writeInt(data.length); out.write(data);
        }
        private void reserve(long added) throws IOException {
            if (added<0 || bytes>limits.cacheBytes()-existing-32-added) throw new LimitReached();
            bytes+=added;
        }
        long bytes() { return bytes+(closed?32:0); }
        void finish() throws IOException {
            if (closed) throw new IOException("Closed metadata writer");
            out.close(); closed=true;
            Files.write(pending,digest.digest(),StandardOpenOption.APPEND);
        }
        void replace() throws IOException {
            if (!closed) throw new IOException("Unfinished metadata writer");
            Files.move(pending,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            moved=true;
        }
        @Override public void close() throws IOException {
            if (!closed) { closed=true; out.close(); }
            if (!moved) Files.deleteIfExists(pending);
        }
    }

    static void delete(Path root) throws IOException {
        for (String name : List.of(FILE,PENDING)) {
            Path path=root.resolve(name);
            if (Files.exists(path,LinkOption.NOFOLLOW_LINKS)) { regular(path); Files.delete(path); }
        }
    }
    private static void regular(Path path) throws IOException {
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Non-regular cache file");
    }
    private static int count(int value,int maximum) throws IOException {
        if (value<0 || value>maximum) throw new IOException("Cached collection/byte limit");
        return value;
    }
    private static <T>T item(T[] values,int ordinal) throws IOException {
        if (ordinal<0 || ordinal>=values.length) throw new IOException("Cached enum");
        return values[ordinal];
    }
    private static void text(DataOutputStream out,String value) throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length>512) throw new IOException("Cached string limit");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int length=count(in.readInt(),512); byte[] bytes=in.readNBytes(length);
        if (bytes.length!=length) throw new EOFException();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(),in.readLong()); }
    private static void uuid(DataOutputStream out,UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }
}
