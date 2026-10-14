package dev.enginehost.plugin.godot;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Desktop check for {@link GodotPackKey}, run on a workstation rather than on
 * a device, because a device is the wrong place to find out whether key
 * recovery works.
 *
 * It exercises the very routine the plugin ships: this file is compiled
 * against the same {@code GodotPackKey} and {@code GodotPackResolver} sources
 * that go into the bundle, not a copy of them.
 *
 * <pre>
 *   cd enginehost-android
 *   javac -d /tmp/godot-keycheck \
 *       app/src/main/java/dev/enginehost/plugin/godot/GodotPackKey.java \
 *       app/src/main/java/dev/enginehost/plugin/godot/GodotPackResolver.java \
 *       tools/GodotPackKeyCheck.java
 *   java -cp /tmp/godot-keycheck dev.enginehost.plugin.godot.GodotPackKeyCheck \
 *       [&lt;game executable&gt; &lt;pack&gt;]
 * </pre>
 *
 * With no arguments it runs the synthetic cases only: packs and executables
 * this file builds itself, with a key it chose, so every case has a known
 * right answer. Given a real game's executable and pack it additionally
 * recovers that game's key and reports whether the key opens the pack's
 * directory. It never prints a key: a real one is reported by a short digest
 * of itself, which is enough to see that two runs agree.
 */
public final class GodotPackKeyCheck {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        File scratch = Files.createTempDirectory("godot-keycheck").toFile();
        try {
            byte[] key = new byte[GodotPackKey.KEY_BYTES];
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) (0x11 * (i + 1));
            }

            checkPackFormat(scratch, key, 2, "4.4.1");
            checkPackFormat(scratch, key, 3, "4.5.1");
            checkCarrier(scratch, key, "pe-aligned", true, true);
            checkCarrier(scratch, key, "pe-unaligned", true, false);
            checkCarrier(scratch, key, "elf-aligned", false, true);
            checkCarrier(scratch, key, "elf-unaligned", false, false);
            checkKeylessExecutable(scratch, key);
            checkUnrecognisedContainer(scratch, key);

            if (args.length == 2) {
                checkRealGame(new File(args[0]), new File(args[1]));
            } else if (args.length != 0) {
                throw new IllegalArgumentException(
                        "usage: GodotPackKeyCheck [<game executable> <pack>]");
            }
        } finally {
            delete(scratch);
        }
        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }

    /** A pack of the given format is read, seen as encrypted, and opened. */
    private static void checkPackFormat(File scratch, byte[] key, int format, String version)
            throws Exception {
        File pack = new File(scratch, "format" + format + ".pck");
        writePack(pack, key, format, version, 0);
        GodotPackResolver.Pack resolved = GodotPackResolver.resolve(pack.getParentFile(),
                pack.getName());
        report("format " + format + " header version", version, resolved.engineVersion);
        report("format " + format + " seen as encrypted", true, resolved.encrypted);
        GodotPackKey.EncryptedDirectory directory =
                GodotPackKey.readEncryptedDirectory(pack, resolved.directoryOffset);
        report("format " + format + " right key opens it", true,
                GodotPackKey.opens(key, directory));
        report("format " + format + " wrong key refused", false,
                GodotPackKey.opens(new byte[GodotPackKey.KEY_BYTES], directory));
        pack.delete();
    }

    /**
     * The key is recovered from a PE or an ELF, whether the linker happened to
     * place it at a 16-byte-aligned address (the fast pass) or not (the
     * exhaustive one).
     */
    private static void checkCarrier(File scratch, byte[] key, String name, boolean pe,
            boolean aligned) throws Exception {
        File pack = new File(scratch, name + ".pck");
        writePack(pack, key, 2, "4.4.1", 0);
        File executable = new File(scratch, name + (pe ? ".exe" : ".x86_64"));
        writeCarrier(executable, key, pe, aligned);

        GodotPackResolver.Pack resolved = GodotPackResolver.resolve(scratch, pack.getName());
        GodotPackKey.EncryptedDirectory directory =
                GodotPackKey.readEncryptedDirectory(pack, resolved.directoryOffset);
        byte[] found = GodotPackKey.recover(executable, directory);
        report(name + " key recovered", GodotPackKey.hex(key),
                found == null ? "nothing" : GodotPackKey.hex(found));
        report(name + " found by name beside the pack", executable.getName(),
                nameOf(GodotPackKey.siblingOf(pack)));
        pack.delete();
        executable.delete();
    }

    /** An executable that never held this game's key yields nothing, not a guess. */
    private static void checkKeylessExecutable(File scratch, byte[] key) throws Exception {
        File pack = new File(scratch, "keyless.pck");
        writePack(pack, key, 2, "4.4.1", 0);
        byte[] otherKey = new byte[GodotPackKey.KEY_BYTES];
        for (int i = 0; i < otherKey.length; i++) {
            otherKey[i] = (byte) (0x07 * (i + 3));
        }
        File executable = new File(scratch, "keyless.exe");
        writeCarrier(executable, otherKey, true, true);
        GodotPackResolver.Pack resolved = GodotPackResolver.resolve(scratch, pack.getName());
        GodotPackKey.EncryptedDirectory directory =
                GodotPackKey.readEncryptedDirectory(pack, resolved.directoryOffset);
        report("another game's executable yields no key", "nothing",
                nameOfKey(GodotPackKey.recover(executable, directory)));
        report("keyless.exe is still seen as an executable", true,
                GodotPackKey.executablesIn(scratch, null).contains(executable));
        pack.delete();
        executable.delete();
    }

    /**
     * A carrier that is neither a PE nor an ELF is searched whole rather than
     * skipped: the MD5 check is what decides, so there is no reason to refuse
     * a container this code does not recognise.
     */
    private static void checkUnrecognisedContainer(File scratch, byte[] key) throws Exception {
        File pack = new File(scratch, "raw.pck");
        writePack(pack, key, 2, "4.4.1", 0);
        File blob = new File(scratch, "raw.bin");
        byte[] bytes = new byte[8192];
        System.arraycopy(key, 0, bytes, 4099, key.length); // Deliberately unaligned.
        Files.write(blob.toPath(), bytes);
        GodotPackResolver.Pack resolved = GodotPackResolver.resolve(scratch, pack.getName());
        GodotPackKey.EncryptedDirectory directory =
                GodotPackKey.readEncryptedDirectory(pack, resolved.directoryOffset);
        byte[] found = GodotPackKey.recover(blob, directory);
        report("unrecognised container searched whole", GodotPackKey.hex(key),
                found == null ? "nothing" : GodotPackKey.hex(found));
        pack.delete();
        blob.delete();
    }

    /** The real thing: a shipped game's executable and its encrypted pack. */
    private static void checkRealGame(File executable, File pack) throws Exception {
        System.out.println("- " + pack.getName());
        GodotPackResolver.Pack resolved =
                GodotPackResolver.resolve(pack.getParentFile(), pack.getName());
        System.out.println("  engine " + resolved.engineVersion
                + ", encrypted directory: " + resolved.encrypted
                + ", directory at " + resolved.directoryOffset);
        if (!resolved.encrypted) {
            System.out.println("  not encrypted: this game needs no key");
            return;
        }
        GodotPackKey.EncryptedDirectory directory =
                GodotPackKey.readEncryptedDirectory(pack, resolved.directoryOffset);
        long started = System.nanoTime();
        byte[] found = GodotPackKey.recover(executable, directory);
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (found == null) {
            report("key recovered from " + executable.getName(), "a key", "nothing");
            return;
        }
        System.out.println("  key recovered in " + millis + " ms, fingerprint "
                + fingerprint(found));
        report("recovered key opens the pack directory", true,
                GodotPackKey.opens(found, directory));
    }

    /** A digest of the key, so runs can be compared without printing it. */
    private static String fingerprint(byte[] key) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            text.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
            text.append(Character.forDigit(hash[i] & 0xF, 16));
        }
        return text.toString();
    }

    // ----------------------------------------------------------------- fixtures

    /**
     * Writes a pack whose file directory is encrypted exactly as Godot's
     * exporter writes one: the directory's plaintext, an MD5 of it, its
     * length, a CFB initialization vector, and the ciphertext.
     */
    private static void writePack(File target, byte[] key, int format, String version,
            long padBefore) throws Exception {
        String[] paths = { "scenes/main.tscn", "art/title.png", "script/player.gd" };
        ByteBuffer plain = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        for (String path : paths) {
            byte[] bytes = path.getBytes("UTF-8");
            int padded = bytes.length + (bytes.length % 4 == 0 ? 0 : 4 - bytes.length % 4);
            plain.putInt(padded);
            plain.put(bytes);
            for (int i = bytes.length; i < padded; i++) {
                plain.put((byte) 0);
            }
            plain.putLong(0); // offset
            plain.putLong(16); // size
            plain.put(new byte[16]); // md5 of the file
            plain.putInt(0); // flags
        }
        byte[] directory = new byte[plain.position()];
        System.arraycopy(plain.array(), 0, directory, 0, directory.length);

        byte[] iv = new byte[16];
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) (i * 3 + 1);
        }
        byte[] md5 = MessageDigest.getInstance("MD5").digest(directory);
        byte[] cipher = encryptCfb(key, iv, directory);

        List<byte[]> parts = new ArrayList<>();
        parts.add(new byte[(int) padBefore]);
        ByteBuffer header = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[] { 'G', 'D', 'P', 'C' });
        header.putInt(format);
        String[] numbers = version.split("\\.");
        header.putInt(Integer.parseInt(numbers[0]));
        header.putInt(Integer.parseInt(numbers[1]));
        header.putInt(Integer.parseInt(numbers[2]));
        header.putInt(1); // PACK_DIR_ENCRYPTED
        header.putLong(0); // file base
        if (format == 3) {
            header.putLong(40 + 24); // directory offset, past a reserved stretch
            header.put(new byte[24]);
        } else {
            header.put(new byte[64]); // sixteen reserved words
        }
        header.putInt(paths.length);
        header.put(md5);
        header.putLong(directory.length);
        header.put(iv);
        header.put(cipher);
        byte[] body = new byte[header.position()];
        System.arraycopy(header.array(), 0, body, 0, body.length);
        parts.add(body);

        try (RandomAccessFile out = new RandomAccessFile(target, "rw")) {
            out.setLength(0);
            for (byte[] part : parts) {
                out.write(part);
            }
        }
    }

    /**
     * Writes a stand-in for a game executable: a minimal PE or ELF with one
     * initialized, non-executable data section holding the key, placed either
     * at a 16-byte-aligned address or deliberately off one.
     */
    private static void writeCarrier(File target, byte[] key, boolean pe, boolean aligned)
            throws IOException {
        int dataBytes = 64 * 1024;
        byte[] data = new byte[dataBytes];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + 7); // Noise, so the key is not the only non-zero run.
        }
        int keyAt = aligned ? 4096 : 4093;
        System.arraycopy(key, 0, data, keyAt, key.length);

        if (pe) {
            int headerBytes = 1024;
            int sectionTable = 0x80 + 24;
            byte[] file = new byte[headerBytes + dataBytes];
            file[0] = 'M';
            file[1] = 'Z';
            putInt(file, 0x3C, 0x80);
            file[0x80] = 'P';
            file[0x81] = 'E';
            putShort(file, 0x84, 0x8664); // Machine: x86-64
            putShort(file, 0x86, 1); // One section
            putShort(file, 0x94, 0); // No optional header, so the table follows
            byte[] name = ".data".getBytes("UTF-8");
            System.arraycopy(name, 0, file, sectionTable, name.length);
            putInt(file, sectionTable + 8, dataBytes); // VirtualSize
            putInt(file, sectionTable + 12, 0x2000); // VirtualAddress, itself aligned
            putInt(file, sectionTable + 16, dataBytes); // SizeOfRawData
            putInt(file, sectionTable + 20, headerBytes); // PointerToRawData
            putInt(file, sectionTable + 36, 0xC0000040); // initialized data, read/write
            System.arraycopy(data, 0, file, headerBytes, dataBytes);
            Files.write(target.toPath(), file);
            return;
        }

        int headerBytes = 1024;
        int sectionTable = 512;
        byte[] file = new byte[headerBytes + dataBytes];
        file[0] = 0x7F;
        file[1] = 'E';
        file[2] = 'L';
        file[3] = 'F';
        file[4] = 2; // 64-bit
        file[5] = 1; // little-endian
        putLong(file, 0x28, sectionTable); // e_shoff
        putShort(file, 0x3A, 64); // e_shentsize
        putShort(file, 0x3C, 1); // e_shnum
        putInt(file, sectionTable + 4, 1); // SHT_PROGBITS
        putLong(file, sectionTable + 8, 0x3); // SHF_ALLOC | SHF_WRITE
        putLong(file, sectionTable + 16, 0x4000); // sh_addr, itself aligned
        putLong(file, sectionTable + 24, headerBytes); // sh_offset
        putLong(file, sectionTable + 32, dataBytes); // sh_size
        System.arraycopy(data, 0, file, headerBytes, dataBytes);
        Files.write(target.toPath(), file);
    }

    /** CFB128 encryption, the inverse of what FileAccessEncrypted reads. */
    private static byte[] encryptCfb(byte[] key, byte[] iv, byte[] plain) throws Exception {
        int padded = plain.length % 16 == 0 ? plain.length : plain.length + 16 - plain.length % 16;
        byte[] input = new byte[padded];
        System.arraycopy(plain, 0, input, 0, plain.length);
        byte[] out = new byte[padded];
        byte[] feedback = iv.clone();
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        for (int at = 0; at < padded; at += 16) {
            byte[] mask = cipher.doFinal(feedback);
            for (int i = 0; i < 16; i++) {
                out[at + i] = (byte) (input[at + i] ^ mask[i]);
            }
            System.arraycopy(out, at, feedback, 0, 16);
        }
        return out;
    }

    // ----------------------------------------------------------------- plumbing

    private static void putShort(byte[] target, int at, int value) {
        target[at] = (byte) value;
        target[at + 1] = (byte) (value >> 8);
    }

    private static void putInt(byte[] target, int at, long value) {
        for (int i = 0; i < 4; i++) {
            target[at + i] = (byte) (value >> (8 * i));
        }
    }

    private static void putLong(byte[] target, int at, long value) {
        for (int i = 0; i < 8; i++) {
            target[at + i] = (byte) (value >> (8 * i));
        }
    }

    private static String nameOf(File file) {
        return file == null ? "nothing" : file.getName();
    }

    private static String nameOfKey(byte[] key) {
        return key == null ? "nothing" : "a key";
    }

    private static void report(String what, Object expected, Object actual) {
        boolean same = String.valueOf(expected).equals(String.valueOf(actual));
        if (!same) {
            failures++;
        }
        System.out.println((same ? "  ok   " : "  FAIL ") + what
                + (same ? "" : " (expected " + expected + ", got " + actual + ")"));
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }

    private GodotPackKeyCheck() {}
}
