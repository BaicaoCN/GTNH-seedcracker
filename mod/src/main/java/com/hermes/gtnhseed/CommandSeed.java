package com.hermes.gtnhseed;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端命令 /gtnhseed —— 手动指定证据文件，避免服务器出生点变动导致"被当成新地图"。
 *
 * /gtnhseed                 查看当前状态
 * /gtnhseed list            列出 config/gtnhseed 下所有证据文件
 * /gtnhseed use <序号>      锁定写入某个文件（跨世界/换地址都不再新建）
 * /gtnhseed auto            恢复自动按地图指纹分文件
 */
public class CommandSeed extends CommandBase {

    @Override
    public String getCommandName() {
        return "gtnhseed";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtnhseed [list|use <序号>|auto|rescan [半径]]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();

        if (sub.equals("list")) {
            List<File> files = GTNHSeedCollector.listEvidenceFiles();
            if (files.isEmpty()) {
                reply(sender, "§e还没有任何证据文件");
                return;
            }
            reply(sender, "§b证据文件（用 /gtnhseed use <序号> 锁定）：");
            for (int i = 0; i < files.size(); i++) {
                reply(sender, String.format("  §f%d) §7%s  §8%s", i + 1, files.get(i).getName(),
                        GTNHSeedCollector.evidenceSummary(files.get(i))));
            }
            return;
        }

        if (sub.equals("use")) {
            if (args.length < 2) {
                reply(sender, "§c用法：/gtnhseed use <序号>（先用 list 查看）");
                return;
            }
            List<File> files = GTNHSeedCollector.listEvidenceFiles();
            int idx;
            try {
                idx = Integer.parseInt(args[1]) - 1;
            } catch (NumberFormatException e) {
                reply(sender, "§c序号必须是数字");
                return;
            }
            if (idx < 0 || idx >= files.size()) {
                reply(sender, "§c序号超出范围（1~" + files.size() + "）");
                return;
            }
            GTNHSeedCollector.pinEvidence(files.get(idx));
            reply(sender, "§a已锁定写入：§f" + files.get(idx).getName() + " §7（/gtnhseed auto 可解除）");
            return;
        }

        if (sub.equals("rescan")) {
            int r = 48;
            if (args.length > 1) {
                try { r = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) { }
            }
            int n = GTNHSeedCollector.rescanAround(r);
            reply(sender, "§a已清除 §f" + n + " §a个区块的扫描记录（半径 " + r + "），会重新扫描");
            return;
        }

        if (sub.equals("auto")) {
            GTNHSeedCollector.unpinEvidence();
            reply(sender, "§a已恢复自动分文件（按 世界类型@出生点）");
            return;
        }

        // status
        reply(sender, "§b当前证据文件：§f" + GTNHSeedCollector.currentEvidenceName()
                + (GTNHSeedCollector.isPinned() ? " §e（已锁定）" : " §7（自动）"));
        reply(sender, "§7已扫描 " + GTNHSeedCollector.scannedCount() + " 区块 · LootGames "
                + GTNHSeedCollector.typeCountPublic(6) + " · 空岛 " + GTNHSeedCollector.typeCountPublic(7)
                + " · 陨石 " + GTNHSeedCollector.typeCountPublic(8));
        reply(sender, "§7/gtnhseed list 查看所有文件，/gtnhseed use <序号> 锁定");
        return;
    }

    private static void reply(ICommandSender sender, String msg) {
        sender.addChatMessage(new ChatComponentText(msg));
    }
}
