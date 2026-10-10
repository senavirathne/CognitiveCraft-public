package dev.aivillages.core.kernel;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.LongSupplier;

import static dev.aivillages.core.kernel.WorldRetentionManager.*;

/** Bounded accounting adapter. It never deletes another domain's persistence or recovery files. */
public final class RetentionFileOwner implements Owner {
    private final String id;
    private final Path directory;
    private final Category category;
    private final LongSupplier revision, records;
    private final int maximumFiles;
    private final long admissionBytes;
    public RetentionFileOwner(String id, Path directory, Category category, LongSupplier revision,
                              LongSupplier records, int maximumFiles) {
        this(id, directory, category, revision, records, maximumFiles, 0);
    }
    public RetentionFileOwner(String id, Path directory, Category category, LongSupplier revision,
                              LongSupplier records, int maximumFiles, long admissionBytes) {
        new Key(id, "records");
        if (maximumFiles < 1 || maximumFiles > 512) throw new IllegalArgumentException("Accounting file cap");
        this.id = id; this.directory = directory; this.category = category;
        this.revision = Objects.requireNonNull(revision); this.records = Objects.requireNonNull(records);
        if (admissionBytes < 0) throw new IllegalArgumentException("Admission ceiling");
        this.maximumFiles = maximumFiles; this.admissionBytes = admissionBytes;
    }
    @Override public String id() { return id; }
    @Override public long revision() { return revision.getAsLong(); }
    @Override public Optional<Amount> admissionCeiling() { return admissionBytes == 0 ? Optional.empty() : Optional.of(new Amount(admissionBytes, 0)); }
    @Override public Cursor open() {
        long captured = revision(); long count = records.getAsLong();
        return new Cursor() {
            private DirectoryStream<Path> stream;
            private Iterator<Path> files;
            private int scanned;
            private boolean started, finished;
            @Override public long revision() { return captured; }
            @Override public Page next(int maximum, int bytes) throws IOException {
                var entries = new ArrayList<Entry>(); int work = 0;
                if (finished) return new Page(entries, true, true, 0, 0);
                if (!started) {
                    started = true;
                    entries.add(new Entry(new Key(id, "records"), category, new Amount(0, count), List.of(), true, false, Set.of(), ""));
                    work++;
                    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                        safePath(directory); stream = Files.newDirectoryStream(directory); files = stream.iterator();
                    }
                }
                while (work < maximum && files != null && files.hasNext()) {
                    Path path = files.next(); work++;
                    if (++scanned > maximumFiles) throw new IOException("Accounting file limit");
                    BasicFileAttributes attributes = attributes(path);
                    entries.add(new Entry(new Key(id, "file:" + path.getFileName()), category,
                            new Amount(attributes.size(), 0), List.of(), true, false, Set.of(), identity(attributes)));
                }
                finished = files == null || !files.hasNext();
                if (finished) close();
                return new Page(entries, finished, RetentionFileOwner.this.revision() == captured, work, 0);
            }
            @Override public void close() throws IOException { if (stream != null) { stream.close(); stream = null; } }
        };
    }
    @Override public OwnerResult collect(Entry entry, long expectedRevision, java.util.function.BooleanSupplier cancel) {
        return OwnerResult.of(OwnerStatus.PROTECTED);
    }
    static void safePath(Path path) throws IOException {
        for (Path component = path.toAbsolutePath(); component != null; component = component.getParent())
            if (Files.isSymbolicLink(component)) throw new IOException("Symbolic retention path");
    }
    static BasicFileAttributes attributes(Path path) throws IOException {
        safePath(path);
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Unexpected retention entry");
        return attributes;
    }
    static String identity(BasicFileAttributes value) {
        return value.size() + ":" + value.lastModifiedTime() + ":" + value.fileKey();
    }
}
