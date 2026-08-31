package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Stable content fingerprint for a pack directory or ZIP archive. */
final class PackFingerprint {
    private PackFingerprint() {}

    static String sha256(Path packPath) throws IOException {
        List<FileEntry> files = new ArrayList<>(Files.isDirectory(packPath)
                ? directoryEntries(packPath)
                : zipEntries(packPath));
        files.sort(Comparator.comparing(FileEntry::name));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (FileEntry file : files) {
                digest.update(file.name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Long.toString(file.bytes().length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(file.bytes());
                digest.update((byte) 0);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static List<FileEntry> directoryEntries(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            return new FileEntry(root.relativize(path).toString().replace('\\', '/'),
                                    Files.readAllBytes(path));
                        } catch (IOException e) {
                            throw new FingerprintFailure(e);
                        }
                    })
                    .toList();
        } catch (FingerprintFailure e) {
            throw e.ioException;
        }
    }

    private static List<FileEntry> zipEntries(Path archive) throws IOException {
        List<FileEntry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (unsafe(name)) {
                    throw new IOException("unsafe ZIP entry path: " + name);
                }
                if (!seen.add(name)) {
                    throw new IOException("duplicate ZIP entry: " + name);
                }
                if (!entry.isDirectory()) {
                    try (var input = zip.getInputStream(entry)) {
                        result.add(new FileEntry(name, input.readAllBytes()));
                    }
                }
            }
        }
        return result;
    }

    private static boolean unsafe(String name) {
        if (name.isBlank() || name.startsWith("/")
                || (name.length() >= 3 && Character.isLetter(name.charAt(0))
                && name.charAt(1) == ':' && name.charAt(2) == '/')) {
            return true;
        }
        for (String component : name.split("/", -1)) {
            if (component.equals("..") || component.indexOf('\0') >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private record FileEntry(String name, byte[] bytes) {}

    private static final class FingerprintFailure extends RuntimeException {
        private final IOException ioException;

        private FingerprintFailure(IOException ioException) {
            this.ioException = ioException;
        }
    }
}
