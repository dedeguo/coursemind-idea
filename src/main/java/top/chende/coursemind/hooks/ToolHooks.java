package top.chende.coursemind.hooks;

import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.runtime.RuntimePaths;
import top.chende.coursemind.runtime.SharedStateLock;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Four local Windows adapters; inspection never creates a tool directory or starts a process. */
public final class ToolHooks {
    public enum Tool {
        TRAE("Trae", "trae-code", ".trae-cn", "hooks.json", true),
        CLAUDE("Claude Code", "claude-code", ".claude", "settings.json", false),
        CODEBUDDY("CodeBuddy", "codebuddy", ".codebuddy", "settings.json", true),
        WORKBUDDY("WorkBuddy", "workbuddy", ".workbuddy", "settings.json", false);
        public final String title, source, directory, file;
        public final boolean selected;
        Tool(String title, String source, String directory, String file, boolean selected) {
            this.title=title; this.source=source; this.directory=directory; this.file=file; this.selected=selected;
        }
    }
    public record Inspection(Tool tool, Path file, boolean available, String message) {}
    public record Preview(Tool tool, boolean remove, String description, byte[] basis, Path restoreBackup, byte[] backupBasis, boolean eligible) {
        public Preview { basis = basis == null ? null : basis.clone(); backupBasis = backupBasis == null ? null : backupBasis.clone(); }
        @Override public byte[] backupBasis() { return backupBasis == null ? null : backupBasis.clone(); }
        @Override public byte[] basis() { return basis == null ? null : basis.clone(); }
    }
    public record Result(Tool tool, boolean failed, String message) {}
    private final Path home;
    private final RuntimePaths paths;
    private final boolean windows;
    public ToolHooks(Path home, RuntimePaths paths, boolean windows) { this.home=home; this.paths=paths; this.windows=windows; }
    public Path file(Tool tool) { return home.resolve(tool.directory).resolve(tool.file); }
    private CodeBuddyHooks adapter(Tool tool) { return new CodeBuddyHooks(file(tool), paths.collectorExe(), tool.source); }
    public Inspection inspect(Tool tool) {
        if (!windows) return new Inspection(tool, file(tool), false, "跳过：仅支持本机 Windows");
        if (!Files.isDirectory(file(tool).getParent())) return new Inspection(tool, file(tool), false, "跳过：未检测到工具目录");
        if (tool == Tool.CLAUDE && !gitBashPresent()) return new Inspection(tool, file(tool), false, "跳过：未检测到 Git Bash，请安装 Git for Windows");
        CodeBuddyHooks.Status status = adapter(tool).inspect();
        if (!status.valid()) return new Inspection(tool, file(tool), true, status.error());
        boolean changed = adapter(tool).plan().changed();
        return new Inspection(tool, file(tool), true, changed ? "待配置或修复（不代表已采集）" : "Hook 已配置，真实采集待验证");
    }
    private boolean gitBashPresent() {
        String override = System.getenv("CLAUDE_CODE_GIT_BASH_PATH");
        if (override != null && Files.isRegularFile(Path.of(override))) return true;
        for (String root : List.of(System.getenv().getOrDefault("ProgramFiles", "C:/Program Files"),
                System.getenv().getOrDefault("LOCALAPPDATA", home.resolve("AppData/Local").toString()))) {
            if (Files.isRegularFile(Path.of(root, "Git/bin/bash.exe")) || Files.isRegularFile(Path.of(root, "Programs/Git/bin/bash.exe"))) return true;
        }
        return false;
    }
    private byte[] bytes(Tool tool) {
        try { return Files.exists(file(tool)) ? Files.readAllBytes(file(tool)) : null; }
        catch (IOException e) { throw new CourseMindException("无法读取工具配置", e); }
    }
    public Preview preview(Tool tool, boolean remove) {
        Inspection state = inspect(tool);
        if (!state.available()) return new Preview(tool, remove, state.message(), bytes(tool), null, null, false);
        byte[] before = bytes(tool);
        CodeBuddyHooks.Plan plan = remove ? adapter(tool).planRemove() : adapter(tool).plan();
        if (!Arrays.equals(before, bytes(tool))) throw new CourseMindException("配置已变化，请重新预览");
        return new Preview(tool, remove, String.join("\n", plan.lines()), before, null, null, true);
    }
    public Preview previewRestore(Tool tool) {
        Inspection state = inspect(tool);
        if (!state.available()) return new Preview(tool, false, state.message(), bytes(tool), null, null, false);
        try {
            byte[] before = bytes(tool);
            Path backup = adapter(tool).latestBackup();
            byte[] backupBytes = Files.readAllBytes(backup);
            String description = String.join("\n", adapter(tool).planRestore(backup).lines());
            if (!Arrays.equals(before, bytes(tool)) || !Arrays.equals(backupBytes, Files.readAllBytes(backup)))
                throw new CourseMindException("预览期间配置或备份被修改，请重试");
            return new Preview(tool, false, description, before, backup, backupBytes, true);
        } catch (IOException e) { throw new CourseMindException("无法预览受管条目恢复", e); }
    }
    public List<Result> apply(List<Preview> previews) {
        List<Result> results = new ArrayList<>();
        for (Preview preview : previews) {
            if (!preview.eligible()) { results.add(new Result(preview.tool(), false, preview.description())); continue; }
            try {
                Result result = SharedStateLock.run(paths, () -> {
                    Inspection state = inspect(preview.tool());
                    if (!state.available()) return new Result(preview.tool(), false, state.message());
                    if (!Arrays.equals(preview.basis(), bytes(preview.tool()))) throw new CourseMindException("预览后配置被修改，请重新预览后重试");
                    if (preview.restoreBackup() != null) {
                        try { if (!Arrays.equals(preview.backupBasis(), Files.readAllBytes(preview.restoreBackup())))
                            throw new CourseMindException("预览后备份被修改，请重新预览"); }
                        catch (IOException e) { throw new CourseMindException("备份不可读，请重新预览", e); }
                    }
                    CodeBuddyHooks.ApplyResult applied = preview.restoreBackup() != null ? adapter(preview.tool()).restore(preview.restoreBackup()) : preview.remove() ? adapter(preview.tool()).remove() : adapter(preview.tool()).apply();
                    return new Result(preview.tool(), false, applied.message() + (applied.backup()==null ? "" : "\n备份：" + applied.backup()));
                });
                results.add(result);
            } catch (RuntimeException e) { results.add(new Result(preview.tool(), true, "失败：" + e.getMessage())); }
        }
        return List.copyOf(results);
    }
}
