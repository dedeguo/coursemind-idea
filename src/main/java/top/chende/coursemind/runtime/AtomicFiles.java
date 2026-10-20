package top.chende.coursemind.runtime;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 共享文件的原子替换：先写同目录临时文件，再 move 覆盖，避免被其他 IDE 读到半个文件。
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void write(@NotNull Path target, byte @NotNull [] content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
