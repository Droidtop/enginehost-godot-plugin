package dev.enginehost.plugin.godot;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Recovers the AES-256 key a Godot game was exported with, from the game's
 * own executable.
 *
 * <h2>Why this is needed</h2>
 *
 * When a project is exported with an encryption key, the pack header's flags
 * word carries {@code PACK_DIR_ENCRYPTED} and the file directory that follows
 * is an encrypted blob. {@code PackedSourcePCK::try_open_pack}
 * (core/io/file_access_pack.cpp) decrypts that directory with the 32 bytes of
 * the global {@code script_encryption_key}, and {@code FileAccessPack}'s
 * constructor decrypts individual encrypted files with the same global. That
 * global is never read from anywhere at run time: it is compiled into the
 * export template by core/core_builders.py from the build's
 * {@code SCRIPT_AES256_ENCRYPTION_KEY}, so the only copy of a given game's key
 * is the one inside the binaries that game shipped with.
 *
 * Enginehost runs its own engine build, whose {@code script_encryption_key} is
 * the default all-zero one, so an encrypted pack fails in
 * {@code ProjectSettings::_setup} with "Can't open encrypted pack directory"
 * unless the game's own key is put in front of it first. This class reads that
 * key back out of the game's executable; the wrapper then hands it to the
 * engine through ENGINEHOST_GODOT_PACK_KEY, which
 * platform/android/java_godot_lib_jni.cpp applies before Main::setup.
 *
 * <h2>How the key is found</h2>
 *
 * {@code script_encryption_key} is a plain {@code uint8_t[32]} global with a
 * non-zero initializer, so the linker stores those 32 bytes verbatim in an
 * initialized-data section of the executable. Existing recovery tools
 * (char-ptr/gdke, GDRE Tools' key finder) start from the machine code that
 * references the array and resolve the reference's target address. That works,
 * but it depends on how one compiler spelled one loop, and has to be re-taught
 * for every architecture and every optimizer change.
 *
 * The data itself is searched here instead, because this problem comes with an
 * exact oracle: the encrypted directory begins with an MD5 of its own
 * plaintext ({@code FileAccessEncrypted::open_and_parse}), so a candidate can
 * simply be tried. Every 32-byte window of every initialized-data section is a
 * candidate, and a candidate is accepted only when decrypting the game's real
 * directory with it reproduces the recorded MD5. A wrong key therefore cannot
 * be returned, whatever the compiler did. Nothing is taken from either tool's
 * code, only the observation that the bytes are in the binary.
 *
 * Two cheap filters keep the search short. The first pass considers only
 * windows at a 16-byte-aligned address, which is where a compiler puts a
 * 32-byte array; the exhaustive pass at every byte offset runs only if that
 * finds nothing. Before any full decrypt, one AES block recovers the first 16
 * plaintext bytes (CFB's first block is {@code C0 XOR E(K, IV)}) and they are
 * checked for the shape of a directory entry: a small path length followed by
 * text. That rejects essentially every candidate for the price of one block.
 *
 * <h2>Never logged</h2>
 *
 * The key is returned to the caller and handed to the engine. It is not
 * written to the log, into a message, or to a file.
 */
public final class GodotPackKey {

    /** How much of the executable is held in memory while searching. */
    private static final int WINDOW_BYTES = 4 << 20;
    /** Bytes read at a time while decrypting the directory for the MD5 check. */
    private static final int DECRYPT_CHUNK_BYTES = 64 << 10;
    /** A directory entry's path is a filename, never megabytes of one. */
    private static final int LONGEST_PLAUSIBLE_PATH = 4096;

    public static final int KEY_BYTES = 32;

    private GodotPackKey() {}

    /**
     * The encrypted file directory of a pack: everything needed both to test a
     * candidate key and, once one passes, to know it was the right one.
     *
     * The layout is {@code FileAccessEncrypted}'s, read without its magic
     * because {@code try_open_pack} opens the directory with
     * {@code p_with_magic} false: a 16-byte MD5 of the plaintext, the
     * plaintext length, the 16-byte CFB initialization vector, then the
     * ciphertext padded up to a whole number of AES blocks.
     */
    public static final class EncryptedDirectory {
        final File file;
        final byte[] md5;
        final long length;
        final byte[] iv;
        final long cipherStart;

        EncryptedDirectory(File file, byte[] md5, long length, byte[] iv, long cipherStart) {
            this.file = file;
            this.md5 = md5;
            this.length = length;
            this.iv = iv;
            this.cipherStart = cipherStart;
        }

        /** The ciphertext's size on disk: the plaintext, padded to 16 bytes. */
        long cipherLength() {
            long padded = length;
            if (padded % 16 != 0) {
                padded += 16 - (padded % 16);
            }
            return padded;
        }
    }

    /**
     * Reads the encrypted directory a pack's header points at.
     *
     * @param pack the .pck, or the executable a pack is appended to
     * @param directoryOffset where the directory's file count sits, as
     *                        {@link GodotPackResolver} computed it from the
     *                        pack header
     */
    public static EncryptedDirectory readEncryptedDirectory(File pack, long directoryOffset)
            throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(pack, "r")) {
            // The file count is stored before the encrypted part: try_open_pack
            // reads it from the plain stream, then wraps the rest in a
            // FileAccessEncrypted.
            long headerStart = directoryOffset + 4;
            if (handle.length() < headerStart + 40) {
                throw new IOException("the pack's encrypted directory is cut short");
            }
            handle.seek(headerStart);
            byte[] header = new byte[40];
            handle.readFully(header);
            byte[] md5 = new byte[16];
            System.arraycopy(header, 0, md5, 0, 16);
            long length = ByteBuffer.wrap(header, 16, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            byte[] iv = new byte[16];
            System.arraycopy(header, 24, iv, 0, 16);
            long cipherStart = headerStart + 40;
            EncryptedDirectory directory =
                    new EncryptedDirectory(pack, md5, length, iv, cipherStart);
            if (length <= 0 || handle.length() < cipherStart + directory.cipherLength()) {
                throw new IOException("the pack's encrypted directory declares an impossible "
                        + "size; the file is probably truncated");
            }
            return directory;
        }
    }

    /**
     * Searches {@code executable} for the key that opens {@code directory}.
     *
     * @return the 32 bytes, or null when this executable does not carry the key
     */
    public static byte[] recover(File executable, EncryptedDirectory directory)
            throws IOException {
        List<Region> regions = dataRegions(executable);
        byte[] found = search(executable, regions, directory, true);
        if (found != null) {
            return found;
        }
        return search(executable, regions, directory, false);
    }

    /** A stretch of the file that may hold the key, and where it is loaded. */
    static final class Region {
        final long fileOffset;
        final long size;
        /** The address this stretch is loaded at, for the alignment pass. */
        final long address;

        Region(long fileOffset, long size, long address) {
            this.fileOffset = fileOffset;
            this.size = size;
            this.address = address;
        }
    }

    private static byte[] search(File executable, List<Region> regions,
            EncryptedDirectory directory, boolean alignedOnly) throws IOException {
        byte[] head = firstCipherBlock(directory);
        // One block cipher, re-keyed per candidate: building a Cipher for each
        // of several million windows costs far more than the block itself.
        Aes aes = new Aes();
        try (RandomAccessFile handle = new RandomAccessFile(executable, "r")) {
            long fileLength = handle.length();
            for (Region region : regions) {
                long end = Math.min(region.fileOffset + region.size, fileLength);
                for (long base = region.fileOffset; base + KEY_BYTES <= end; base += WINDOW_BYTES) {
                    int take = (int) Math.min(WINDOW_BYTES + (long) KEY_BYTES - 1, end - base);
                    byte[] window = new byte[take];
                    handle.seek(base);
                    handle.readFully(window);
                    int limit = Math.min(WINDOW_BYTES, window.length - KEY_BYTES + 1);
                    for (int i = 0; i < limit; i++) {
                        long address = region.address + (base - region.fileOffset) + i;
                        if (alignedOnly != (address % 16 == 0)) {
                            continue;
                        }
                        if (isAllSame(window, i)) {
                            continue;
                        }
                        byte[] candidate = Arrays.copyOfRange(window, i, i + KEY_BYTES);
                        if (!plausible(aes, candidate, head, directory)) {
                            continue;
                        }
                        if (opens(candidate, directory)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Runs of one repeated byte are the padding between globals, and an
     * all-zero window is the key an engine built without one carries. Neither
     * is worth an AES call.
     */
    private static boolean isAllSame(byte[] window, int at) {
        byte first = window[at];
        for (int i = 1; i < KEY_BYTES; i++) {
            if (window[at + i] != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the first plaintext block this candidate produces looks like the
     * start of a pack directory: a 32-bit path length, then the path itself.
     *
     * CFB's first plaintext block is the first ciphertext block XOR the block
     * cipher applied to the initialization vector, so this costs one AES
     * block rather than a decrypt of the whole directory.
     */
    private static boolean plausible(Aes aes, byte[] candidate, byte[] head,
            EncryptedDirectory directory) throws IOException {
        aes.keyed(candidate);
        byte[] mask = aes.block(directory.iv);
        byte[] plain = new byte[16];
        for (int i = 0; i < 16; i++) {
            plain[i] = (byte) (head[i] ^ mask[i]);
        }
        long pathLength =
                ByteBuffer.wrap(plain, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        if (pathLength < 1 || pathLength > LONGEST_PLAUSIBLE_PATH) {
            return false;
        }
        for (int i = 4; i < 16; i++) {
            int b = plain[i] & 0xFF;
            // The path is UTF-8 text, zero-padded to a 4-byte boundary.
            boolean text = b >= 0x20 || b == 0;
            if (!text || b == 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static byte[] firstCipherBlock(EncryptedDirectory directory) throws IOException {
        byte[] head = new byte[16];
        try (RandomAccessFile handle = new RandomAccessFile(directory.file, "r")) {
            handle.seek(directory.cipherStart);
            handle.readFully(head);
        }
        return head;
    }

    /**
     * Whether this candidate really is the key: decrypts the whole directory
     * and compares the MD5 the pack recorded, which is the check
     * {@code FileAccessEncrypted::open_and_parse} makes before it will hand the
     * directory to the engine. Nothing else can tell a right key from a wrong
     * one, so nothing else is trusted.
     */
    public static boolean opens(byte[] candidate, EncryptedDirectory directory)
            throws IOException {
        if (candidate.length != KEY_BYTES) {
            return false;
        }
        MessageDigest md5 = digest();
        Aes aes = new Aes();
        aes.keyed(candidate);
        byte[] feedback = directory.iv.clone();
        byte[] next = new byte[16];
        long remaining = directory.length;
        long cipherRemaining = directory.cipherLength();
        byte[] chunk = new byte[DECRYPT_CHUNK_BYTES];
        try (RandomAccessFile handle = new RandomAccessFile(directory.file, "r")) {
            handle.seek(directory.cipherStart);
            while (cipherRemaining > 0) {
                int take = (int) Math.min(chunk.length, cipherRemaining);
                handle.readFully(chunk, 0, take);
                cipherRemaining -= take;
                for (int at = 0; at < take; at += 16) {
                    byte[] mask = aes.block(feedback);
                    System.arraycopy(chunk, at, next, 0, 16);
                    for (int i = 0; i < 16; i++) {
                        chunk[at + i] = (byte) (chunk[at + i] ^ mask[i]);
                    }
                    System.arraycopy(next, 0, feedback, 0, 16);
                }
                int digestible = (int) Math.min(take, remaining);
                md5.update(chunk, 0, digestible);
                remaining -= digestible;
            }
        }
        return MessageDigest.isEqual(md5.digest(), directory.md5);
    }

    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("this device has no MD5", impossible);
        }
    }

    /**
     * One AES block, encrypting. Both the filter and the full decrypt need the
     * block cipher rather than a mode: CFB is built from forward AES calls in
     * either direction, which is why FileAccessEncrypted sets an encode key
     * schedule to decrypt.
     */
    private static final class Aes {
        private final Cipher cipher;

        Aes() throws IOException {
            try {
                cipher = Cipher.getInstance("AES/ECB/NoPadding");
            } catch (GeneralSecurityException error) {
                throw new IOException("this device cannot do AES-256", error);
            }
        }

        void keyed(byte[] key) throws IOException {
            try {
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            } catch (GeneralSecurityException error) {
                throw new IOException("this device cannot do AES-256", error);
            }
        }

        byte[] block(byte[] input) throws IOException {
            try {
                return cipher.doFinal(input);
            } catch (GeneralSecurityException error) {
                throw new IOException("this device cannot do AES-256", error);
            }
        }
    }

    /**
     * The initialized, non-executable parts of a PE or ELF executable, which is
     * where a linker puts an array like {@code script_encryption_key}.
     *
     * Writable sections come first. {@code script_encryption_key} is a mutable
     * global, so that is where it has to be ({@code .data}), and a game's
     * {@code .data} is a fraction of the size of the read-only data beside it:
     * on the Linux build of Anomalous Coffee Machine 2 that ordering is the
     * difference between a second and nearly two minutes. Read-only sections
     * are still searched afterwards, so a build that somehow made the array
     * constant is found too, just not as quickly.
     *
     * A file that parses as neither is searched whole rather than refused: the
     * key is wherever it is, and the MD5 check is what decides.
     */
    static List<Region> dataRegions(File executable) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(executable, "r")) {
            List<Region> regions = peDataRegions(handle);
            if (regions.isEmpty()) {
                regions = elfDataRegions(handle);
            }
            if (regions.isEmpty()) {
                regions = new ArrayList<>();
                regions.add(new Region(0, handle.length(), 0));
            }
            return regions;
        }
    }

    private static List<Region> peDataRegions(RandomAccessFile handle) throws IOException {
        List<Region> regions = new ArrayList<>();
        List<Region> readOnly = new ArrayList<>();
        long length = handle.length();
        if (length < 0x40 || read16(handle, 0) != 0x5A4D) {
            return regions; // "MZ"
        }
        long headerAt = read32(handle, 0x3C);
        if (headerAt <= 0 || headerAt + 24 > length || read32(handle, headerAt) != 0x00004550L) {
            return regions; // "PE\0\0"
        }
        int sectionCount = read16(handle, headerAt + 6);
        int optionalHeaderBytes = read16(handle, headerAt + 20);
        long sectionTable = headerAt + 24 + optionalHeaderBytes;
        for (int i = 0; i < sectionCount; i++) {
            long entry = sectionTable + (long) i * 40;
            if (entry + 40 > length) {
                break;
            }
            long virtualAddress = read32(handle, entry + 12);
            long rawSize = read32(handle, entry + 16);
            long rawOffset = read32(handle, entry + 20);
            long flags = read32(handle, entry + 36);
            boolean initialized = (flags & 0x00000040L) != 0; // IMAGE_SCN_CNT_INITIALIZED_DATA
            boolean executable = (flags & 0x20000000L) != 0; // IMAGE_SCN_MEM_EXECUTE
            if (!initialized || executable || rawSize == 0 || rawOffset == 0) {
                continue;
            }
            boolean writable = (flags & 0x80000000L) != 0; // IMAGE_SCN_MEM_WRITE
            (writable ? regions : readOnly).add(new Region(rawOffset, rawSize, virtualAddress));
        }
        regions.addAll(readOnly);
        return regions;
    }

    private static List<Region> elfDataRegions(RandomAccessFile handle) throws IOException {
        List<Region> regions = new ArrayList<>();
        List<Region> readOnly = new ArrayList<>();
        long length = handle.length();
        if (length < 0x34) {
            return regions;
        }
        byte[] ident = new byte[16];
        handle.seek(0);
        handle.readFully(ident);
        if (ident[0] != 0x7F || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') {
            return regions;
        }
        boolean sixtyFour = ident[4] == 2;
        if (ident[5] != 1) {
            return regions; // Only little-endian hosts ship Godot desktop exports.
        }
        long sectionTable = sixtyFour ? read64(handle, 0x28) : read32(handle, 0x20);
        int entryBytes = read16(handle, sixtyFour ? 0x3A : 0x2E);
        int sectionCount = read16(handle, sixtyFour ? 0x3C : 0x30);
        if (sectionTable <= 0 || entryBytes <= 0) {
            return regions;
        }
        for (int i = 0; i < sectionCount; i++) {
            long entry = sectionTable + (long) i * entryBytes;
            if (entry + entryBytes > length) {
                break;
            }
            long type = read32(handle, entry + 4);
            long flags = sixtyFour ? read64(handle, entry + 8) : read32(handle, entry + 8);
            long address = sixtyFour ? read64(handle, entry + 16) : read32(handle, entry + 12);
            long offset = sixtyFour ? read64(handle, entry + 24) : read32(handle, entry + 16);
            long size = sixtyFour ? read64(handle, entry + 32) : read32(handle, entry + 20);
            boolean progbits = type == 1; // SHT_PROGBITS
            boolean allocated = (flags & 0x2L) != 0; // SHF_ALLOC
            boolean executable = (flags & 0x4L) != 0; // SHF_EXECINSTR
            if (!progbits || !allocated || executable || size == 0) {
                continue;
            }
            boolean writable = (flags & 0x1L) != 0; // SHF_WRITE
            (writable ? regions : readOnly).add(new Region(offset, size, address));
        }
        regions.addAll(readOnly);
        return regions;
    }

    private static int read16(RandomAccessFile handle, long at) throws IOException {
        handle.seek(at);
        byte[] bytes = new byte[2];
        handle.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    private static long read32(RandomAccessFile handle, long at) throws IOException {
        handle.seek(at);
        byte[] bytes = new byte[4];
        handle.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private static long read64(RandomAccessFile handle, long at) throws IOException {
        handle.seek(at);
        byte[] bytes = new byte[8];
        handle.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    /** The 64 hexadecimal characters the engine expects, from 32 bytes. */
    public static String hex(byte[] key) {
        StringBuilder text = new StringBuilder(key.length * 2);
        for (byte b : key) {
            text.append(Character.forDigit((b >> 4) & 0xF, 16));
            text.append(Character.forDigit(b & 0xF, 16));
        }
        return text.toString();
    }

    /**
     * The files in a game folder that could carry the key: every regular file
     * that begins like a PE or an ELF. Matching on shape rather than on a list
     * of suffixes covers the extensionless Linux exports as well as
     * {@code .exe}, {@code .x86_64} and {@code .arm64}.
     *
     * @param preferred tried first when it is one of them: the carrier of an
     *                  embedded pack, or the executable named like the pack
     */
    public static List<File> executablesIn(File gameRoot, File preferred) {
        List<File> found = new ArrayList<>();
        File[] entries = gameRoot.listFiles();
        if (entries == null) {
            entries = new File[0];
        }
        Arrays.sort(entries, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File entry : entries) {
            if (!entry.isFile() || !looksExecutable(entry)) {
                continue;
            }
            if (preferred != null && entry.getName().equals(preferred.getName())) {
                continue;
            }
            found.add(entry);
        }
        if (preferred != null && preferred.isFile() && looksExecutable(preferred)) {
            found.add(0, preferred);
        }
        return found;
    }

    /**
     * The executable a loose pack belongs to, by the name the export gave both:
     * {@code game.pck} beside {@code game.exe} or {@code game.x86_64}.
     */
    public static File siblingOf(File pack) {
        File parent = pack.getParentFile();
        File[] entries = parent == null ? null : parent.listFiles();
        if (entries == null) {
            return null;
        }
        String stem = stemOf(pack.getName());
        for (File entry : entries) {
            if (!entry.isFile() || entry.equals(pack) || !looksExecutable(entry)) {
                continue;
            }
            if (stemOf(entry.getName()).equals(stem)) {
                return entry;
            }
        }
        return null;
    }

    private static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        return (dot <= 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    private static boolean looksExecutable(File file) {
        try (RandomAccessFile handle = new RandomAccessFile(file, "r")) {
            if (handle.length() < 64) {
                return false;
            }
            byte[] magic = new byte[4];
            handle.readFully(magic);
            boolean pe = magic[0] == 'M' && magic[1] == 'Z';
            boolean elf = magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
            return pe || elf;
        } catch (IOException unreadable) {
            return false;
        }
    }
}
