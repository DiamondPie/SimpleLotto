package com.diamondpie.simplelotto;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class Main extends JavaPlugin implements CommandExecutor {

    private boolean isRunning = false;
    private long endTime = 0;
    private int currentPot = 0;
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> pendingConfirmation = new HashSet<>();
    private final Map<UUID, Boolean> confirmToggle = new HashMap<>(); // true = 需要确认, false = 不需要

    // Config variables
    private Material currencyMaterial;
    private int costAmount;
    private int initialPot;
    private int minPlayers;
    private int durationSeconds;
    private int intervalSeconds;
    private List<Integer> broadcastTimes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        // 注册命令
        Objects.requireNonNull(getCommand("lotto")).setExecutor(this);

        // 启动自动循环任务
        startCycleTimer();

        getLogger().info("SimpleLotto has been enabled!");
    }

    @Override
    public void onDisable() {
        // 如果插件关闭时还在运行，则取消并退款，防止物品丢失
        if (isRunning) {
            cancelLotto(Bukkit.getConsoleSender());
        }
        getLogger().info("SimpleLotto has been disabled!");
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        String matName = config.getString("currency-item", "minecraft:diamond");
        currencyMaterial = Material.matchMaterial(matName);
        if (currencyMaterial == null) {
            getLogger().severe("配置的物品ID无效: " + matName + "，将默认使用钻石。");
            currencyMaterial = Material.DIAMOND;
        }

        costAmount = config.getInt("cost-amount", 1);
        initialPot = config.getInt("initial-pot", 0);
        minPlayers = config.getInt("min-players", 0);
        durationSeconds = config.getInt("duration-seconds", 300);
        intervalSeconds = config.getInt("interval-seconds", 600);
        broadcastTimes = config.getIntegerList("broadcast-times");
    }

    // 自动循环计时器
    private void startCycleTimer() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isEnabled()) {
                    this.cancel();
                    return;
                }

                // 如果正在运行，不处理
                if (isRunning) return;

                // 检查在线人数
                if (Bukkit.getOnlinePlayers().size() >= minPlayers) {
                    startLotto(null);
                }
            }
        }.runTaskTimer(this, 20L * intervalSeconds, 20L * intervalSeconds);
        // 注意：这里简单的逻辑是每次间隔检查一次。
        // 如果想要更精确的“结束->等待->开始”，需要在endLotto里调度下一个start任务，
        // 但为了代码简洁和robust（防止调度断链），这里使用周期性检查。
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendInfo(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "join":
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§c只有玩家可以参与乐透。");
                    return true;
                }
                handleJoin((Player) sender);
                break;
            case "start":
                if (!sender.hasPermission("lotto.admin")) {
                    sender.sendMessage("§c你没有权限执行此命令。");
                    return true;
                }
                startLotto(sender);
                break;
            case "end":
                if (!sender.hasPermission("lotto.admin")) {
                    sender.sendMessage("§c你没有权限执行此命令。");
                    return true;
                }
                endLotto(sender);
                break;
            case "cancel":
                if (!sender.hasPermission("lotto.admin")) {
                    sender.sendMessage("§c你没有权限执行此命令。");
                    return true;
                }
                cancelLotto(sender);
                break;
            case "toggleconfirm":
                if (!(sender instanceof Player)) return true;
                handleToggleConfirm((Player) sender);
                break;
            case "help":
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void sendInfo(CommandSender sender) {
        sender.sendMessage("§8§m--------------------------------");
        sender.sendMessage("§6§l🎲 乐透系统状态");
        if (isRunning) {
            long secondsLeft = (endTime - System.currentTimeMillis()) / 1000;
            if (secondsLeft < 0) secondsLeft = 0;

            sender.sendMessage("§e状态: §a正在进行中");
            sender.sendMessage("§e距离开奖: §b" + formatTime(secondsLeft));
            sender.sendMessage("§e当前奖池: §d" + currentPot + " " + getItemName());
            sender.sendMessage("§e参与人数: §b" + participants.size());
            sender.sendMessage("§e参与费用: §c" + costAmount + " " + getItemName());
        } else {
            sender.sendMessage("§e状态: §7未开始");
            sender.sendMessage("§e下一轮: §7等待中...");
        }
        sender.sendMessage("§8§m--------------------------------");
    }

    private void handleToggleConfirm(Player player) {
        boolean current = confirmToggle.getOrDefault(player.getUniqueId(), true);
        confirmToggle.put(player.getUniqueId(), !current);
        if (!current) {
            player.sendMessage("§a[乐透] 已开启参与确认功能。");
        } else {
            player.sendMessage("§e[乐透] 已关闭参与确认功能，输入 /lotto join 将直接扣费。");
        }
    }

    private void handleJoin(Player player) {
        if (!isRunning) {
            player.sendMessage("§c[乐透] 当前没有正在进行的乐透活动。");
            return;
        }

        if (participants.contains(player.getUniqueId())) {
            player.sendMessage("§c[乐透] 你已经参与了本次乐透！");
            return;
        }

        boolean needConfirm = confirmToggle.getOrDefault(player.getUniqueId(), true);

        // 如果需要确认，且不在等待确认列表中
        if (needConfirm && !pendingConfirmation.contains(player.getUniqueId())) {
            pendingConfirmation.add(player.getUniqueId());
            player.sendMessage("§e[乐透] 参与乐透需要消耗 §c" + costAmount + " " + getItemName() + "§e。");
            player.sendMessage("§e[乐透] 请再次输入 §b/lotto join §e以确认参与。");

            // 10秒后清除确认状态
            new BukkitRunnable() {
                @Override
                public void run() {
                    pendingConfirmation.remove(player.getUniqueId());
                }
            }.runTaskLater(this, 200L);
            return;
        }

        // 检查物品是否足够
        if (!player.getInventory().containsAtLeast(new ItemStack(currencyMaterial), costAmount)) {
            player.sendMessage("§c[乐透] 你的背包中没有足够的 " + getItemName() + " (需要 " + costAmount + " 个)。");
            pendingConfirmation.remove(player.getUniqueId());
            return;
        }

        // 扣除物品
        player.getInventory().removeItem(new ItemStack(currencyMaterial, costAmount));

        // 加入逻辑
        participants.add(player.getUniqueId());
        pendingConfirmation.remove(player.getUniqueId()); // 成功后移除等待确认状态
        currentPot += costAmount; // 增加奖池，但不增加系统初始资金

        player.sendMessage("§a[乐透] 成功参与！当前奖池已达 §d" + currentPot + " " + getItemName() + "§a！");
    }

    private void startLotto(CommandSender starter) {
        if (isRunning) {
            if (starter != null) starter.sendMessage("§c乐透已经在运行中！");
            return;
        }

        isRunning = true;
        currentPot = initialPot;
        participants.clear();
        pendingConfirmation.clear();
        endTime = System.currentTimeMillis() + (durationSeconds * 1000L);

        // 全局播报
        Bukkit.broadcast(Component.text("§8§m--------------------------------"));
        Bukkit.broadcast(Component.text("§6§l🎉 乐透活动开始！"));
        Bukkit.broadcast(Component.text("§e输入 §b/lotto join §e参与！"));
        Bukkit.broadcast(Component.text("§e参与费用: §c" + costAmount + " " + getItemName()));
        Bukkit.broadcast(Component.text("§e初始奖池: §d" + initialPot + " " + getItemName()));
        Bukkit.broadcast(Component.text("§e开奖时间: §a" + durationSeconds + "秒后"));
        Bukkit.broadcast(Component.text("§8§m--------------------------------"));

        // 启动倒计时任务
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    this.cancel();
                    return;
                }

                long timeLeftMillis = endTime - System.currentTimeMillis();

                // 时间到，结束
                if (timeLeftMillis <= 0) {
                    endLotto(null);
                    this.cancel();
                    return;
                }

                // 播报检查
                long secondsLeft = timeLeftMillis / 1000;
                // 加1秒的容差防止跳秒
                if (broadcastTimes.contains((int) secondsLeft) || broadcastTimes.contains((int) secondsLeft + 1)) {
                    // 简单的防重复播报逻辑（每秒只跑一次）
                    // 实际上runTaskTimer并不是绝对精确，这里简化处理，直接判断int匹配
                    if (broadcastTimes.contains((int) secondsLeft)) {
                        sendBroadcastUpdate((int) secondsLeft);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void sendBroadcastUpdate(int secondsLeft) {
        Bukkit.broadcast(Component.text("§e[乐透] 距离开奖仅剩 §c" + formatTime(secondsLeft) + " §e！"));
        Bukkit.broadcast(Component.text("§e当前奖池: §d" + currentPot + " " + getItemName() + " §7(参与人数: " + participants.size() + ")"));
    }

    private void endLotto(CommandSender ender) {
        if (!isRunning) {
            if (ender != null) ender.sendMessage("§c乐透未在运行！");
            return;
        }

        isRunning = false;

        Bukkit.broadcast(Component.text("§8§m--------------------------------"));
        Bukkit.broadcast(Component.text("§6§l🎲 乐透开奖时刻！"));

        if (participants.isEmpty()) {
            Bukkit.broadcast(Component.text("§c很遗憾，本次乐透无人参与，奖池作废。"));
            Bukkit.broadcast(Component.text("§8§m--------------------------------"));
            return;
        }

        // 随机抽取
        List<UUID> participantList = new ArrayList<>(participants);
        UUID winnerUUID = participantList.get(new Random().nextInt(participantList.size()));
        Player winner = Bukkit.getPlayer(winnerUUID);
        OfflinePlayer offlineWinner = Bukkit.getOfflinePlayer(winnerUUID);

        String winnerName = (winner != null) ? winner.getName() : offlineWinner.getName();

        Bukkit.broadcast(Component.text("§e恭喜玩家 §a§l" + winnerName + " §e成为了幸运儿！"));
        Bukkit.broadcast(Component.text("§e他独揽了奖池内的 §d§l" + currentPot + " " + getItemName() + "§e！"));
        Bukkit.broadcast(Component.text("§8§m--------------------------------"));

        // 发放奖励
        giveReward(winnerUUID, currentPot);
    }

    private void cancelLotto(CommandSender canceler) {
        if (!isRunning) {
            if (canceler != null) canceler.sendMessage("§c乐透未在运行！");
            return;
        }

        isRunning = false;

        Bukkit.broadcast(Component.text("§c[乐透] 本次乐透已被管理员取消。所有参与费用将退还。"));

        // 退款
        for (UUID uuid : participants) {
            giveReward(uuid, costAmount);
        }
        participants.clear();
    }

    // 发放物品（如果背包满则掉落）
    private void giveReward(UUID uuid, int amount) {
        Player player = Bukkit.getPlayer(uuid);
        ItemStack reward = new ItemStack(currencyMaterial, amount);

        if (player != null && player.isOnline()) {
            HashMap<Integer, ItemStack> leftOver = player.getInventory().addItem(reward);
            if (!leftOver.isEmpty()) {
                for (ItemStack item : leftOver.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
                player.sendMessage("§c[乐透] 背包已满，部分物品已掉落在脚下！");
            } else {
                if (amount == costAmount) {
                    player.sendMessage("§a[乐透] 已退还参与费用。");
                } else {
                    player.sendMessage("§a[乐透] 奖励已发放至背包！");
                }
            }
        } else {
            // 对于离线玩家，这里简化处理：不操作或需要依赖第三方数据库
            // 为保证安全，建议生产环境使用 PersistentDataContainer 或 Vault
            // 此处代码仅在控制台警告，实际生产中应配合数据库在玩家上线时给予
            getLogger().warning("玩家 " + uuid + " 离线，无法发放乐透物品/退款 (" + amount + "个)！请手动处理。");
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6§lSimpleLotto 帮助");
        sender.sendMessage("§e/lotto §7- 查看当前乐透状态");
        sender.sendMessage("§e/lotto join §7- 参与乐透");
        sender.sendMessage("§e/lotto toggleconfirm §7- 开启/关闭参与二次确认");
        if (sender.hasPermission("lotto.admin")) {
            sender.sendMessage("§c/lotto start §7- [OP] 手动开始");
            sender.sendMessage("§c/lotto end §7- [OP] 手动开奖");
            sender.sendMessage("§c/lotto cancel §7- [OP] 取消并退款");
        }
    }

    private String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d", m, s);
    }

    private String getItemName() {
        // 简单的名称格式化，比如 DIAMOND -> Diamond
        String name = currencyMaterial.name().toLowerCase().replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }
}