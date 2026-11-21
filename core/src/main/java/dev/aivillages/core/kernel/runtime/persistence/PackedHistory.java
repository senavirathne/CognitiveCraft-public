package dev.aivillages.core.kernel.runtime.persistence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import static dev.aivillages.core.kernel.runtime.persistence.ArchiveFormat.*;
import static dev.aivillages.core.kernel.runtime.persistence.PersistencePolicy.*;

/** A bounded storage representation of exact, independently addressable history rows. */
final class PackedHistory {
    private static final int MAGIC = 0x43435031; // CCP1
    private static final int ROW_HEADER = 20;
    private static final int HEADER = 8;

    private PackedHistory() { }

    static Optional<Row> pack(List<Row> rows) {
        if(rows.isEmpty() || rows.size()>SEGMENT_ROWS || rows.stream().anyMatch(row->row.kind()!=Kind.HISTORY))
            throw new IllegalArgumentException("Only bounded closed history may be packed");
        var payloads=rows.stream().map(Row::payload).toList();
        int size=4+ROW_HEADER*rows.size()+payloads.stream().mapToInt(payload->payload.length).sum();
        if(size>SEGMENT_BYTES)return Optional.empty();
        var raw=ByteBuffer.allocate(size);
        raw.putInt(rows.size());
        for(int index=0;index<rows.size();index++) {
            var id=rows.get(index).id();
            var payload=payloads.get(index);
            raw.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putInt(payload.length).put(payload);
        }
        var body=new ByteArrayOutputStream();
        try(var deflater=new Deflater()) {
            deflater.setInput(raw.array());
            deflater.finish();
            byte[] buffer=new byte[4096];
            while(!deflater.finished()) {
                int count=deflater.deflate(buffer);
                if(count<1 || body.size()+count+HEADER>RECORD_BYTES)return Optional.empty();
                body.write(buffer,0,count);
            }
        }
        if(body.size()+HEADER>=size)return Optional.empty();
        var packed=ByteBuffer.allocate(body.size()+HEADER).putInt(MAGIC).putInt(size).put(body.toByteArray());
        return Optional.of(new Row(Kind.PACKED_HISTORY,UUID.nameUUIDFromBytes(raw.array()),packed.array()));
    }

    static List<Row> unpack(Row packed) throws IOException {
        byte[] bytes=packed.payload();
        var header=ByteBuffer.wrap(bytes);
        if(packed.kind()!=Kind.PACKED_HISTORY || bytes.length<=HEADER || header.getInt()!=MAGIC)
            throw new IOException("Unknown packed history");
        int size=header.getInt();
        if(size<4+ROW_HEADER+1 || size>SEGMENT_BYTES)throw new IOException("Packed history extent");
        byte[] uncompressed=new byte[size];
        try(var inflater=new Inflater()) {
            inflater.setInput(bytes,HEADER,bytes.length-HEADER);
            int offset=0;
            while(offset<size && !inflater.finished()) {
                int count=inflater.inflate(uncompressed,offset,size-offset);
                if(count==0)break;
                offset+=count;
            }
            if(offset!=size || !inflater.finished() || inflater.getRemaining()!=0
                    || !packed.id().equals(UUID.nameUUIDFromBytes(uncompressed)))throw new IOException("Packed history content identity");
        }catch(DataFormatException invalid){throw new IOException("Invalid packed history",invalid);}
        var raw=ByteBuffer.wrap(uncompressed);
        int count=raw.getInt();
        if(count<1 || count>SEGMENT_ROWS)throw new IOException("Packed history row count");
        var rows=new ArrayList<Row>(count);
        var seen=new HashSet<UUID>();
        for(int index=0;index<count;index++) {
            if(raw.remaining()<ROW_HEADER)throw new IOException("Truncated packed history");
            UUID id=new UUID(raw.getLong(),raw.getLong());
            int length=raw.getInt();
            if(length<1 || length>RECORD_BYTES || length>raw.remaining() || !seen.add(id))
                throw new IOException("Invalid original packed row");
            byte[] payload=new byte[length];
            raw.get(payload);
            rows.add(new Row(Kind.HISTORY,id,payload));
        }
        if(raw.hasRemaining())throw new IOException("Trailing packed history");
        return List.copyOf(rows);
    }
}
