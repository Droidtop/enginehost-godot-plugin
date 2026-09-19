package dev.enginehost.plugin.godot;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Which desktop platform a game was exported for, read from the game's own
 * executable, as the name Godot's OS.get_name() gives on that platform.
 *
 * The engine presents this platform to the game instead of Android (see
 * OS_Android::get_name in platform/android/os_android.cpp for why).
 */
final class GodotExportPlatform {
    private GodotExportPlatform() {}

    /**
     * @param pack the resolved pack; its carrier when the pack is embedded,
     *             otherwise the executable the export named to match it, is
     *             asked first, then every other executable in its folder
     * @return "Windows", "Linux" or "macOS"; null when the folder holds no
     *         executable to tell by (a pack on its own)
     */
    static String of(File gameRoot, GodotPackResolver.Pack pack) {
        File folder = pack.file.getParentFile() == null ? gameRoot : pack.file.getParentFile();
        File preferred = pack.embedded ? pack.file : GodotPackKey.siblingOf(pack.file);
        for (File executable : GodotPackKey.executablesIn(folder, preferred)) {
            String platform = ofExecutable(executable);
            if (platform != null) return platform;
        }
        return null;
    }

    /** By container format: PE is Windows, ELF is Linux, Mach-O (thin or universal) is macOS. */
    private static String ofExecutable(File executable) {
        byte[] head = new byte[4];
        try (InputStream in = new FileInputStream(executable)) {
            if (in.read(head) < 4) return null;
        } catch (IOException unreadable) {
            return null;
        }
        if (head[0] == 'M' && head[1] == 'Z') return "Windows";
        if (head[0] == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') return "Linux";
        int magic = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        if (magic == 0xFEEDFACF || magic == 0xCFFAEDFE || magic == 0xFEEDFACE || magic == 0xCEFAEDFE
                || magic == 0xCAFEBABE) {
            return "macOS";
        }
        return null;
    }
}
