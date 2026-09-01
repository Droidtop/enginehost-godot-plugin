package dev.enginehost.plugin.godot;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Finds the Godot pack for a game folder, including the case Godot's own
 * default export produces: a single self-contained executable with the
 * .pck appended to it.
 *
 * That form has no {@code .pck} and no {@code project.godot}, so looking
 * only for those misses the most common way Godot games are shipped.
 * Goodbye Eternity is the motivating case -- a 1.2 GB {@code .x86_64}
 * and a 2.3 GB {@code .exe}, each with the pack inside.
 *
 * <h2>Format</h2>
 *
 * A self-contained export ends with an 8-byte little-endian pack size
 * followed by the ASCII magic {@code GDPC}. The pack itself begins at
 * {@code fileLength - 12 - packSize} with {@code GDPC}, a 32-bit pack
 * format version, then the engine major/minor/patch as three more 32-bit
 * values. Verified against Goodbye Eternity: pack format 3, engine 4.5.1.
 *
 * The host architecture of the carrier binary is irrelevant. An x86_64
 * Linux executable is fine on an ARM device because only the appended
 * data is read; the machine code is never loaded.
 */
public final class GodotPackResolver {

    /** Trailer: 8-byte size + 4-byte magic. */
    private static final int TRAILER_BYTES = 12;
    private static final byte[] MAGIC = { 'G', 'D', 'P', 'C' };

    /** Where the pack is, and which engine built it. */
    public static final class Pack {
        public final File file;
        /** Null when the pack is a standalone .pck the engine can open directly. */
        public final String engineVersion;

        Pack(File file, String engineVersion) {
            this.file = file;
            this.engineVersion = engineVersion;
        }
    }

    private GodotPackResolver() {}

    /**
     * Resolves the pack for {@code gameRoot}, extracting an embedded one
     * into {@code cacheDir} when necessary.
     *
     * @return the pack, or null when the folder is a loose project
     *         ({@code project.godot}) that Godot should open with --path.
     */
    public static Pack resolve(File gameRoot, File cacheDir) throws IOException {
        File explicit = firstMatching(gameRoot, ".pck", ".zip");
        if (explicit != null) {
            return new Pack(explicit, readEngineVersion(explicit, 0));
        }
        if (new File(gameRoot, "project.godot").isFile()) {
            return null;
        }
        File carrier = firstEmbeddedCarrier(gameRoot);
        if (carrier == null) {
            throw new IOException(
                    "No Godot pack here: expected a .pck, a project.godot, or an "
                            + "executable with an embedded pack");
        }
        return extractEmbedded(carrier, cacheDir);
    }

    /** The engine version an embedded or standalone pack declares, or null. */
    public static String readEngineVersion(File file, long packStart) {
        try (RandomAccessFile handle = new RandomAccessFile(file, "r")) {
            handle.seek(packStart);
            byte[] header = new byte[20];
            handle.readFully(header);
            if (!startsWithMagic(header)) return null;
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            buffer.position(8); // skip magic + pack format version
            int major = buffer.getInt();
            int minor = buffer.getInt();
            // Patch sits immediately after; read it separately to keep the
            // header read short for standalone packs.
            handle.seek(packStart + 16);
            byte[] patchBytes = new byte[4];
            handle.readFully(patchBytes);
            int patch = ByteBuffer.wrap(patchBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
            return major + "." + minor + "." + patch;
        } catch (IOException error) {
            return null;
        }
    }

    /**
     * The pack embedded in a self-contained export under {@code gameRoot},
     * extracted into {@code cacheDir}. Null when no file there carries one.
     */
    public static Pack resolveEmbedded(File gameRoot, File cacheDir) throws IOException {
        File carrier = firstEmbeddedCarrier(gameRoot);
        return carrier == null ? null : extractEmbedded(carrier, cacheDir);
    }

    private static Pack extractEmbedded(File carrier, File cacheDir) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(carrier, "r")) {
            long length = handle.length();
            handle.seek(length - TRAILER_BYTES);
            byte[] trailer = new byte[TRAILER_BYTES];
            handle.readFully(trailer);
            ByteBuffer buffer = ByteBuffer.wrap(trailer).order(ByteOrder.LITTLE_ENDIAN);
            long packSize = buffer.getLong();
            if (trailer[8] != 'G' || trailer[9] != 'D' || trailer[10] != 'P' || trailer[11] != 'C') {
                throw new IOException("Executable has no embedded Godot pack");
            }
            long packStart = length - TRAILER_BYTES - packSize;
            if (packStart < 0 || packSize <= 0) {
                throw new IOException("Embedded Godot pack has an implausible size");
            }
            String engineVersion = readEngineVersion(carrier, packStart);

            // Extracted once per carrier, keyed by name+size so a replaced
            // or updated game re-extracts rather than reusing a stale pack.
            File target = new File(cacheDir,
                    sanitize(carrier.getName()) + "-" + packSize + ".pck");
            if (target.isFile() && target.length() == packSize) {
                return new Pack(target, engineVersion);
            }
            File partial = new File(target.getPath() + ".partial");
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                throw new IOException("Could not create the pack cache directory");
            }
            handle.seek(packStart);
            try (OutputStream out = Files.newOutputStream(partial.toPath())) {
                copyExactly(handle, out, packSize);
            }
            // Rename only after a complete copy, so an interrupted
            // extraction never leaves a truncated pack that looks valid.
            if (!partial.renameTo(target)) {
                throw new IOException("Could not finalise the extracted pack");
            }
            return new Pack(target, engineVersion);
        }
    }

    private static void copyExactly(RandomAccessFile source, OutputStream out, long bytes)
            throws IOException {
        byte[] chunk = new byte[1 << 16];
        long remaining = bytes;
        while (remaining > 0) {
            int wanted = (int) Math.min(chunk.length, remaining);
            int read = source.read(chunk, 0, wanted);
            if (read < 0) throw new IOException("Embedded pack ended early");
            out.write(chunk, 0, read);
            remaining -= read;
        }
    }

    private static File firstEmbeddedCarrier(File gameRoot) {
        File[] entries = gameRoot.listFiles();
        if (entries == null) return null;
        for (File entry : entries) {
            if (!entry.isFile() || entry.length() <= TRAILER_BYTES) continue;
            if (hasPackTrailer(entry)) return entry;
        }
        return null;
    }

    private static boolean hasPackTrailer(File file) {
        try (RandomAccessFile handle = new RandomAccessFile(file, "r")) {
            handle.seek(handle.length() - 4);
            byte[] magic = new byte[4];
            handle.readFully(magic);
            return magic[0] == MAGIC[0] && magic[1] == MAGIC[1]
                    && magic[2] == MAGIC[2] && magic[3] == MAGIC[3];
        } catch (IOException error) {
            return false;
        }
    }

    private static boolean startsWithMagic(byte[] header) {
        return header.length >= 4 && header[0] == MAGIC[0] && header[1] == MAGIC[1]
                && header[2] == MAGIC[2] && header[3] == MAGIC[3];
    }

    private static File firstMatching(File root, String... suffixes) {
        File[] entries = root.listFiles();
        if (entries == null) return null;
        for (File entry : entries) {
            if (!entry.isFile()) continue;
            String name = entry.getName().toLowerCase(Locale.ROOT);
            for (String suffix : suffixes) {
                if (name.endsWith(suffix)) return entry;
            }
        }
        return null;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
