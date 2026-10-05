import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Read-only fingerprint of a Voxy RocksDB storage directory: entry counts per column family (and per
 * LOD level for world_sections) plus a SHA-256 over every key and value in iteration order.
 *
 * Usage: java -cp rocksdbjni.jar VoxyStorageFingerprint.java &lt;storage-dir&gt;
 * The directory should be closed (no running game). Output is one JSON object on stdout.
 */
public final class VoxyStorageFingerprint {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: VoxyStorageFingerprint <closed Voxy storage directory>");
            System.exit(2);
        }
        String path = Path.of(args[0]).toAbsolutePath().toString();
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        try (Options options = new Options()) {
            for (byte[] name : RocksDB.listColumnFamilies(options, path)) {
                descriptors.add(new ColumnFamilyDescriptor(name));
            }
        }
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        StringBuilder json = new StringBuilder("{");
        try (DBOptions options = new DBOptions();
             RocksDB db = RocksDB.openReadOnly(options, path, descriptors, handles);
             ReadOptions read = new ReadOptions()) {
            boolean firstFamily = true;
            for (int i = 0; i < handles.size(); i++) {
                String family = new String(descriptors.get(i).getName(), StandardCharsets.UTF_8);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count = 0;
                TreeMap<Integer, Long> perLevel = new TreeMap<>();
                try (RocksIterator it = db.newIterator(handles.get(i), read)) {
                    for (it.seekToFirst(); it.isValid(); it.next()) {
                        byte[] key = it.key();
                        byte[] value = it.value();
                        digest.update(ByteBuffer.allocate(8).putInt(key.length).putInt(value.length).array());
                        digest.update(key);
                        digest.update(value);
                        count++;
                        if ("world_sections".equals(family) && key.length == 8) {
                            // Voxy stores the section id byte-reversed; the LOD level is the top four bits.
                            perLevel.merge((key[0] & 0xF0) >>> 4, 1L, Long::sum);
                        }
                    }
                }
                if (!firstFamily) json.append(',');
                firstFamily = false;
                json.append("\"").append(family).append("\":{\"entries\":").append(count)
                        .append(",\"sha256\":\"").append(hex(digest.digest())).append("\"");
                if (!perLevel.isEmpty()) {
                    json.append(",\"perLevel\":{");
                    boolean firstLevel = true;
                    for (var entry : perLevel.entrySet()) {
                        if (!firstLevel) json.append(',');
                        firstLevel = false;
                        json.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
                    }
                    json.append('}');
                }
                json.append('}');
            }
            for (ColumnFamilyHandle handle : handles) handle.close();
        }
        System.out.println(json.append('}'));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }
}
