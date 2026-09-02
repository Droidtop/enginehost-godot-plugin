package dev.enginehost.plugin.godot;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Finds the Godot pack for a game folder, including the case Godot's own
 * default export produces: a single self-contained executable with the
 * .pck appended to it.
 *
 * That form has no {@code .pck} and no {@code project.godot}, so looking
 * only for those misses the most common way Godot games are shipped.
 * Goodbye Eternity is the motivating case: a 1.2 GB {@code .x86_64} and a
 * 2.3 GB {@code .exe}, each with the pack inside.
 *
 * <h2>Format</h2>
 *
 * A self-contained export ends with an 8-byte little-endian pack size
 * followed by the ASCII magic {@code GDPC}. The pack itself begins at
 * {@code fileLength - 12 - packSize} with {@code GDPC}, a 32-bit pack
 * format version, then the engine major/minor/patch as three more 32-bit
 * values. Verified against Goodbye Eternity: pack format 3, engine 4.5.1.
 *
 * <h2>No extraction</h2>
 *
 * Godot's own {@code PackedSourcePCK::try_open_pack} looks for that same
 * trailer at the end of whatever {@code --main-pack} names, so the carrier
 * is handed to the engine as it lies. Copying a gigabyte into app storage
 * on first launch would buy nothing and would introduce a disk-space
 * failure where there is currently none.
 *
 * The host architecture of the carrier binary is irrelevant. An x86_64
 * Linux executable is fine on an ARM device because only the appended
 * data is read; the machine code is never loaded.
 */
public final class GodotPackResolver {

    /** Trailer: 8-byte size + 4-byte magic. */
    private static final int TRAILER_BYTES = 12;
    private static final byte[] MAGIC = { 'G', 'D', 'P', 'C' };

    /** What Godot should be pointed at, and which engine built it. */
    public static final class Pack {
        /** The file to pass to {@code --main-pack}. */
        public final File file;
        /** The engine version the pack header declares, or null if unreadable. */
        public final String engineVersion;
        /** True when the pack is appended to a self-contained executable. */
        public final boolean embedded;

        Pack(File file, String engineVersion, boolean embedded) {
            this.file = file;
            this.engineVersion = engineVersion;
            this.embedded = embedded;
        }
    }

    private GodotPackResolver() {}

    /**
     * The single pack-resolution entry point.
     *
     * @param gameRoot the game folder as Enginehost resolved it
     * @param execFile the folder's configured entry, relative to
     *                 {@code gameRoot}; null or blank when unset
     * @return the pack to load, or null when the folder is a loose project
     *         ({@code project.godot}) that Godot should open with
     *         {@code --path}
     * @throws IOException when nothing here is loadable, with a message
     *         that says what was looked for
     */
    public static Pack resolve(File gameRoot, String execFile) throws IOException {
        if (execFile != null && !execFile.isBlank()) {
            return describe(confined(gameRoot, execFile));
        }
        File standalone = onlyStandalonePack(gameRoot);
        if (standalone != null) {
            return describe(standalone);
        }
        if (new File(gameRoot, "project.godot").isFile()) {
            return null;
        }
        File carrier = firstEmbeddedCarrier(gameRoot);
        if (carrier == null) {
            throw new IOException(
                    "this folder holds no Godot game: expected a project.godot, a "
                            + "single .pck or .zip, or a Godot export with the pack "
                            + "inside it. If the game is in a subfolder, point the "
                            + "folder's execFile at the export itself.");
        }
        return describe(carrier);
    }

    /**
     * Whether {@code file} is loadable by Godot, and what it declares.
     *
     * A standalone pack carries its header at offset 0; a self-contained
     * export carries it at the offset its trailer points to.
     */
    private static Pack describe(File file) throws IOException {
        String standalone = readEngineVersion(file, 0);
        if (standalone != null) {
            return new Pack(file, standalone, false);
        }
        long start = embeddedPackStart(file);
        return new Pack(file, readEngineVersion(file, start), true);
    }

    /** The engine version an embedded or standalone pack declares, or null. */
    public static String readEngineVersion(File file, long packStart) {
        try (RandomAccessFile handle = new RandomAccessFile(file, "r")) {
            if (handle.length() < packStart + 20) {
                return null;
            }
            handle.seek(packStart);
            byte[] header = new byte[20];
            handle.readFully(header);
            if (!startsWithMagic(header)) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            buffer.position(8); // skip magic + pack format version
            int major = buffer.getInt();
            int minor = buffer.getInt();
            int patch = buffer.getInt();
            return major + "." + minor + "." + patch;
        } catch (IOException error) {
            return null;
        }
    }

    /**
     * Where the pack appended to {@code carrier} begins.
     *
     * @throws IOException when the file carries no pack, or names one that
     *         does not fit inside it
     */
    private static long embeddedPackStart(File carrier) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(carrier, "r")) {
            long length = handle.length();
            if (length <= TRAILER_BYTES) {
                throw new IOException(
                        quoted(carrier) + " is too small to hold a Godot pack");
            }
            handle.seek(length - TRAILER_BYTES);
            byte[] trailer = new byte[TRAILER_BYTES];
            handle.readFully(trailer);
            if (trailer[8] != 'G' || trailer[9] != 'D' || trailer[10] != 'P' || trailer[11] != 'C') {
                throw new IOException(quoted(carrier)
                        + " is not a Godot game: it is neither a .pck nor an export "
                        + "with a pack inside it");
            }
            long packSize = ByteBuffer.wrap(trailer).order(ByteOrder.LITTLE_ENDIAN).getLong();
            long packStart = length - TRAILER_BYTES - packSize;
            if (packSize <= 0 || packStart < 0) {
                throw new IOException("the pack inside " + quoted(carrier)
                        + " declares an impossible size; the file is probably "
                        + "truncated or still copying");
            }
            if (readEngineVersion(carrier, packStart) == null) {
                throw new IOException("the pack inside " + quoted(carrier)
                        + " does not start where its trailer says; the file is "
                        + "probably damaged");
            }
            return packStart;
        }
    }

    /** The configured entry, resolved inside the folder it belongs to. */
    private static File confined(File gameRoot, String relative) throws IOException {
        if (new File(relative).isAbsolute()) {
            throw new IOException("the execFile set for this folder must be relative to it");
        }
        File file = new File(gameRoot, relative).getCanonicalFile();
        String root = gameRoot.getCanonicalPath() + File.separator;
        if (!file.getPath().startsWith(root)) {
            throw new IOException("the execFile set for this folder points outside it");
        }
        if (!file.isFile()) {
            throw new IOException("the execFile set for this folder names "
                    + quoted(relative) + ", which is not there");
        }
        return file;
    }

    /** The folder's only .pck or .zip, or null when there is not exactly one. */
    private static File onlyStandalonePack(File root) {
        File[] entries = root.listFiles();
        if (entries == null) {
            return null;
        }
        File found = null;
        for (File entry : entries) {
            if (!entry.isFile()) {
                continue;
            }
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".pck") && !name.endsWith(".zip")) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = entry;
        }
        return found;
    }

    private static File firstEmbeddedCarrier(File gameRoot) {
        File[] entries = gameRoot.listFiles();
        if (entries == null) {
            return null;
        }
        for (File entry : entries) {
            if (!entry.isFile() || entry.length() <= TRAILER_BYTES) {
                continue;
            }
            if (hasPackTrailer(entry)) {
                return entry;
            }
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

    private static String quoted(File file) {
        return quoted(file.getName());
    }

    private static String quoted(String name) {
        return "\"" + name + "\"";
    }
}
