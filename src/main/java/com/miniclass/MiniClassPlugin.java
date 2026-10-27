package com.miniclass;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.block.Block;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MiniClassPlugin extends JavaPlugin implements Listener {

    /** GUI 标题标识，用于事件过滤（避免依赖本地化全文匹配） */
    private static final String GUI_TITLE_ID = "选择职业";

    private NamespacedKey classKey;
    // 使用并发安全的 ConcurrentHashMap，防止未来引入异步逻辑时出现数据竞争
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> passiveCD = new ConcurrentHashMap<>();
    private final Map<UUID, String> states = new ConcurrentHashMap<>();
    // 全局共享的随机实例（Random 线程安全），避免频繁 new Random()
    private final Random random = new Random();

    // 内存数据注册表（LinkedHashMap 保持英雄注册顺序，保证 GUI 槽位稳定）
    private final Map<String, HeroData> heroes = new LinkedHashMap<>();
    private final Map<String, SkillData> skills = new HashMap<>();
    // 玩家职业持久化：重启/换服后仍能自动恢复装备
    private final Map<UUID, String> playerClassMap = new ConcurrentHashMap<>();

    // ================= 地狱魔兽（demon）运行时状态 =================
    /** 返祖形态玩家集合（不在集合中 = 初始形态） */
    private final Set<UUID> demonAtavism = ConcurrentHashMap.newKeySet();
    /** 兽护/兽怒增益到期时间戳（毫秒） */
    private final Map<UUID, Long> demonBuffEnd = new ConcurrentHashMap<>();
    /** 兽爪命中刷新增益的内置CD（每玩家1秒最多刷新一次，防高频触发） */
    private final Map<UUID, Long> demonClawRefresh = new ConcurrentHashMap<>();
    /** 蓄力冲刺进行中：下一次近战命中改为结算随机冲刺伤害 */
    private final Set<UUID> demonDashActive = ConcurrentHashMap.newKeySet();
    /** 【兽怒】易伤状态到期时间戳（毫秒），期间受到伤害+60% */
    private final Map<UUID, Long> demonVulnEnd = new ConcurrentHashMap<>();
    /** 永久生命提升V修饰符（固定UUID，便于幂等添加与移除） */
    private static final UUID HEALTH_BOOST_DEMON = UUID.fromString("a7d3f2c1-5b8e-4a2d-9c6f-1e0d3a5b7c9f");
    /** 返祖形态武器名称标记（用于识别当前形态发放的兽爪） */
    private static final String DEMON_CLAW_ATV_NAME = "\u00a7c\u00a7l\u517D\u722A\u00b7\u8fd4\u7956"; // §c§l兽爪·返祖

    @Override
    public void onEnable() {
        classKey = new NamespacedKey(this, "mc_item");
        getServer().getPluginManager().registerEvents(this, this);
        if (this.getCommand("miniclass") != null) {
            this.getCommand("miniclass").setExecutor(this);
        } else {
            getLogger().severe("未在 plugin.yml 中找到命令 miniclass，菜单命令将不可用！");
        }

        // 注册所有英雄和技能数据 (极限压缩硬编码)
        registerAllHeroes();
        registerAllSkills();

        // 启动时校验：报告引用了未注册技能或材质非法的英雄配置
        validateRegistrations();

        // 加载玩家职业选择记录
        loadPlayerClasses();

        getLogger().info("职业缩小版终极版已加载！共 " + heroes.size() + " 个英雄、"
                + skills.size() + " 个技能。");
    }

    @Override
    public void onDisable() {
        // 落盘玩家职业记录，再清理运行时状态
        savePlayerClasses();
        cooldowns.clear();
        passiveCD.clear();
        states.clear();
    }

    // ================= 玩家职业持久化 =================
    private void loadPlayerClasses() {
        FileConfiguration cfg = getConfig();
        for (String uuid : cfg.getKeys(false)) {
            String classId = cfg.getString(uuid);
            if (classId != null && heroes.containsKey(classId)) {
                try {
                    playerClassMap.put(UUID.fromString(uuid), classId);
                } catch (IllegalArgumentException ignored) {
                    // 非法 UUID 键，跳过
                }
            }
        }
        if (!playerClassMap.isEmpty()) {
            getLogger().info("已恢复 " + playerClassMap.size() + " 条玩家职业记录。");
        }
    }

    private void savePlayerClasses() {
        FileConfiguration cfg = getConfig();
        for (Map.Entry<UUID, String> e : playerClassMap.entrySet()) {
            cfg.set(e.getKey().toString(), e.getValue());
        }
        saveConfig();
    }

    /** 将玩家当前手持/穿戴物品与所选职业装备比对，判断是否仍持有职业 */
    private boolean hasHeroGear(Player player, String classId) {
        HeroData hero = heroes.get(classId);
        if (hero == null) return false;
        for (String eq : hero.equipData) {
            String[] p = eq.split(":");
            if (p.length < 3) continue;
            Material mat = Material.matchMaterial(p[1]);
            if (mat == null) continue;
            String slot = p[0];
            ItemStack item;
            switch (slot) {
                case "HELMET": item = player.getInventory().getHelmet(); break;
                case "CHEST":  item = player.getInventory().getChestplate(); break;
                case "LEGS":   item = player.getInventory().getLeggings(); break;
                case "BOOTS":  item = player.getInventory().getBoots(); break;
                default:       item = player.getInventory().getItemInMainHand(); break;
            }
            if (item != null && item.getType() == mat) return true;
        }
        return false;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String classId = playerClassMap.get(player.getUniqueId());
        if (classId == null || !heroes.containsKey(classId)) return;
        // 死亡重生后自动恢复职业装备（下一 tick 执行，确保重生位置/库存已就绪）
        Bukkit.getScheduler().runTaskLater(this, () -> restoreClass(player, classId), 1L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String classId = playerClassMap.get(player.getUniqueId());
        if (classId == null || !heroes.containsKey(classId)) return;
        // 重进服务器：若背包里已无该职业装备（如服务器崩溃导致掉落），自动补发
        if (!hasHeroGear(player, classId)) {
            Bukkit.getScheduler().runTaskLater(this, () -> restoreClass(player, classId), 20L);
        }
    }

    /** 恢复职业装备：不清空背包、不随机传送（与 selectClass 的区别所在） */
    private void restoreClass(Player player, String classId) {
        if (!player.isOnline()) return;
        HeroData hero = heroes.get(classId);
        if (hero == null) return;
        giveHeroEquipment(player, hero);
        if ("demon".equals(classId)) applyDemonPassives(player); // 死亡/重连恢复时同样补上被动增益
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        player.setHealth(maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0);
        player.sendMessage(ChatColor.YELLOW + "已自动恢复职业 [" + hero.name + "] 的装备！");
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "该命令只能由玩家执行。");
            return true;
        }
        openGUI((Player) sender);
        return true;
    }

    /** 启动自检：检查英雄配置中的技能 ID 与附魔名是否合法，输出警告便于排查 */
    private void validateRegistrations() {
        for (Map.Entry<String, HeroData> entry : heroes.entrySet()) {
            for (String eq : entry.getValue().equipData) {
                String[] p = eq.split(":");
                if (p.length < 3) {
                    getLogger().warning("英雄 " + entry.getKey() + " 存在格式错误的装备项: " + eq);
                    continue;
                }
                if (Material.matchMaterial(p[1].trim()) == null) {
                    getLogger().warning("英雄 " + entry.getKey() + " 存在非法材质: " + p[1]);
                }
                // 技能ID可能位于第5段（无属性修饰）或第6段（带属性修饰），逐一检查非空且非属性的字段
                for (int i = 4; i < p.length; i++) {
                    String f = p[i].trim();
                    if (f.isEmpty() || f.contains(";")) continue;
                    if (!skills.containsKey(f)) {
                        getLogger().warning("英雄 " + entry.getKey() + " 装备 " + p[2] + " 引用了未注册的技能 ID: " + f);
                    }
                }
            }
        }
    }

    // ================= 1. 数据注册引擎 (65个英雄硬编码区) =================
    private void registerAllHeroes() {
        // 格式: ID, 名称, 图标材质, 装备数据(槽位:材质:名称:附魔:技能ID)
        // 指引者（基础射手）：头盔+3护甲/水下呼吸I；胸甲+3护甲/弹射物保护II；靴子+1护甲/摔落保护X
        // 天弓：力量I+无限；匕首：攻击伤害+5、攻速-2.5、时运I；技能与被动见下方注册与监听器
        regHero("guide", "指引者", Material.IRON_HELMET,
                "HELMET:IRON_HELMET:&e指引战盔:WATER_BREATHING:1:ARMOR;3",
                "CHEST:IRON_CHESTPLATE:&c指引战甲:PROJECTILE_PROTECTION:2:ARMOR;3",
                "BOOTS:IRON_BOOTS:&e指引战靴:FALL_PROTECTION:10:ARMOR;1",
                "MAIN:BOW:&6指引天弓:ARROW_DAMAGE:1,ARROW_INFINITE:1",
                "INV:IRON_SWORD:&c指引匕首:DAMAGE_ALL:5:ATTACK_DAMAGE;5,ATTACK_SPEED;-2.5,LOOT_BONUS_BLOCKS:1",
                "INV:ENDER_PEARL:&6庇护之瞬::guide_shelter",
                "INV:MUSHROOM_STEW:&b疗养汤药::guide_soup");

        // 地狱魔兽（基础战士）：四件兽皮带火焰保护V；默认/返祖双形态，切换时按形态重新发放装备
        // （见 demon_transform 技能与 giveHeroEquipment 中的恶魔装备定制分支）
        regHero("demon", "地狱魔兽", Material.NETHERITE_SCRAP,
                "HELMET:LEATHER_HELMET:&c兽皮:FIRE_PROTECTION:5:ARMOR;1",
                "CHEST:LEATHER_CHESTPLATE:&c兽皮:FIRE_PROTECTION:5,PROJECTILE_PROTECTION:2,PROTECTION:1:ARMOR;3",
                "LEGS:LEATHER_LEGGINGS:&c兽皮:FIRE_PROTECTION:5,PROJECTILE_PROTECTION:2,PROTECTION_EXPLOSIONS:2,PROTECTION:1:ARMOR;2",
                "BOOTS:LEATHER_BOOTS:&c兽皮:FIRE_PROTECTION:5:ARMOR;1",
                "MAIN:GOLDEN_AXE:&c兽爪:DAMAGE_ALL:2.5:demon_claw",
                "INV:COOKED_BEEF:&c兽之再生::demon_regen",
                "INV:RED_DYE:&c兽欲返祖::demon_transform");

        regHero("enderman", "末影使者", Material.CARVED_PUMPKIN,
                "HELMET:CARVED_PUMPKIN:&5末影帽:PROJECTILE_PROTECTION:2",
                "MAIN:NETHERITE_SWORD:&5虚空拳:KNOCKBACK:2,DAMAGE_ALL:5:ender_punch");

        regHero("ghost", "鬼灵", Material.PURPLE_DYE,
                "MAIN:IRON_SWORD:&8鬼灵匕:DAMAGE_ALL:8:ghost_dagger",
                "INV:ENDER_PEARL:&8影舞::ghost_dance",
                "INV:BLAZE_POWDER:&8影袭::ghost_strike");

        regHero("soul", "魂", Material.DIAMOND_SWORD,
                "BOOTS:NETHERITE_BOOTS:&8魂鞋:PROTECTION_EXPLOSIONS:2,FALL_PROTECTION:10",
                "MAIN:NETHERITE_SWORD:&8魂刃:KNOCKBACK:2,DAMAGE_ALL:4:soul_blade",
                "INV:ENDER_PEARL:&8魂斗移::soul_dash",
                "INV:ENDER_EYE:&8凝神::soul_focus");

        regHero("mermaid", "人鱼公主", Material.PRISMARINE_SHARD,
                "CHEST:DIAMOND_CHESTPLATE:&b海洋战甲:DEPTH_STRIDER:10,PROJECTILE_PROTECTION:3",
                "MAIN:TRIDENT:&b人鱼法杖::mermaid_staff",
                "INV:WATER_BUCKET:&b浪袭::mermaid_wave",
                "INV:MUSIC_DISC_13:&b人鱼诞歌::mermaid_song");

        regHero("vanseda", "凡赛达", Material.NETHERITE_AXE,
                "HELMET:NETHERITE_HELMET:&c战兽头颅:FIRE_PROTECTION:3,PROJECTILE_PROTECTION:3",
                "MAIN:NETHERITE_AXE:&c狂烈战斧:DAMAGE_ALL:9:vanseda_axe",
                "INV:RED_BANNER:&c血腥契约::vanseda_pact");

        regHero("catlord", "猫爷", Material.ORANGE_DYE,
                "MAIN:RABBIT_FOOT:&6猫之爪::cat_claw",
                "INV:SUGAR:&6血脉急行::cat_sprint",
                "INV:MILK_BUCKET:&6疾跳::cat_jump");

        regHero("dawn", "黎明", Material.IRON_SWORD,
                "MAIN:IRON_SWORD:&f黎明刀刃:DAMAGE_ALL:1:dawn_blade",
                "INV:BLACK_DYE:&f阴暗角落::dawn_hide",
                "INV:ENDER_PEARL:&f瞬光闪::dawn_flash");

        regHero("mechanic", "机关大师", Material.DISPENSER,
                "MAIN:FLINT_AND_STEEL:&7s686-机关::mech_gun",
                "INV:SNOWBALL:&7制造闪光::mech_craft_flash",
                "INV:IRON_INGOT:&7自我维修::mech_repair");

        // 第 2 批 (11-20)
        regHero("smoker", "老烟鬼", Material.FISHING_ROD, "MAIN:FISHING_ROD:&8拉烟枪::smoker_gun", "INV:IRON_SWORD:&8烟硝:KNOCKBACK:1,DAMAGE_ALL:7", "INV:BLAZE_POWDER:&8吐云圈::smoker_ring");
        regHero("freewing", "自由之翼", Material.FEATHER, "MAIN:IRON_SWORD:&b立体剑刃:DAMAGE_ALL:5:wing_blade", "INV:FEATHER:&b弹射::wing_dash", "INV:CLOCK:&b机械维护::wing_maintain");
        regHero("puppeteer", "戏说傀师", Material.STRING, "MAIN:LEAD:&d缝线插针::puppet_needle", "INV:STRING:&d操纵一切::puppet_control", "INV:TNT:&d核心爆破::puppet_blast");
        regHero("swordsman", "刀侠", Material.IRON_SWORD, "MAIN:IRON_SWORD:&c拔刀剑·妖刀:DAMAGE_ALL:1:sword_blade", "INV:SUGAR:&c行之移::sword_move", "INV:ENDER_PEARL:&c侠义步::sword_step");
        regHero("fighter", "格斗小子", Material.RABBIT_HIDE, "MAIN:RABBIT_HIDE:&6拳劲::fighter_punch", "INV:SHIELD:&6御气之盾::fighter_shield");
        regHero("shielder", "御守盾", Material.SHIELD, "MAIN:IRON_BLOCK:&9攻型盾:DAMAGE_ALL:3:shield_atk", "INV:PISTON:&9大地之震::shield_quake", "INV:SLIME_BALL:&9英勇盾冲::shield_charge");
        regHero("ranger", "森林守护者", Material.BOW, "MAIN:BOW:&a森林圣弓:ARROW_INFINITE:1:ranger_bow", "INV:FEATHER:&a森跃::ranger_jump", "INV:TOTEM_OF_UNDYING:&a森之守护灵::ranger_spirit");
        regHero("sniper", "狙神", Material.CROSSBOW, "MAIN:CROSSBOW:&7[卍]KAR-98k::sniper_rifle", "INV:IRON_SHOVEL:&7特种兵铲:KNOCKBACK:1", "INV:COMPASS:&7静心步::sniper_focus");
        regHero("hunter", "猎影师", Material.IRON_AXE, "MAIN:IRON_AXE:&4猎影斧:KNOCKBACK:5,DAMAGE_ALL:6:hunter_axe", "INV:CROSSBOW:&4连弩-三连爆发::hunter_crossbow", "INV:NAUTILUS_SHELL:&4本源护体::hunter_source");
        regHero("windlady", "风女", Material.BOW, "MAIN:BOW:&b风灵弓:ARROW_DAMAGE:3,ARROW_INFINITE:1,PIERCING:4:wind_bow", "INV:FEATHER:&b风之控制::wind_control");

        // 第 3 批 (21-30)
        regHero("vampire", "吸血蝗虫", Material.REDSTONE, "MAIN:IRON_SWORD:&4嗜血魔牙::vamp_fang", "INV:FEATHER:&4血意狩猎::vamp_hunt", "INV:REDSTONE:&4渴血领域::vamp_domain");
        regHero("heroking", "英雄王", Material.NETHER_STAR, "MAIN:IRON_SWORD:&6蓄拳:DAMAGE_ALL:3:king_punch", "INV:NETHER_STAR:&6超英觉醒::king_awaken");
        regHero("shahun", "刹魂", Material.IRON_SWORD, "MAIN:IRON_SWORD:&0刹魂剑:LOOT_BONUS_MOBS:1:sha_sword", "INV:SNOWBALL:&0魂-标::sha_mark", "INV:ENDER_PEARL:&0魂移::sha_move");
        regHero("shieldgirl", "盾娘", Material.DIAMOND_SWORD, "MAIN:IRON_SWORD:&d盾刀:DAMAGE_ALL:8:girl_knife", "INV:SHIELD:&d地神之力::girl_power", "INV:DIAMOND:&d凝神器::girl_condense");
        regHero("kungfufox", "功夫神狐", Material.RABBIT_FOOT, "MAIN:RABBIT_FOOT:&e太极八卦掌::fox_palm", "INV:FEATHER:&e扑杀::fox_pounce");
        regHero("lightson", "光之子", Material.DIAMOND_SWORD, "MAIN:DIAMOND_SWORD:&6光之刃:DAMAGE_ALL:3:light_blade", "INV:FEATHER:&6光越::light_leap");
        regHero("hundredbow", "百弓使", Material.BOOK, "MAIN:BOOK:&a百弓书::bow_book", "INV:ARROW:&a箭矢制造器::bow_craft");
        regHero("swordmaster", "剑圣", Material.DIAMOND_SWORD, "MAIN:DIAMOND_SWORD:&b圣剑:DAMAGE_ALL:4:master_sword", "INV:FEATHER:&b踏剑移::master_step", "INV:BOOK:&b藏剑心::master_hide");
        regHero("poisonlord", "毒帝", Material.ARROW, "MAIN:ARROW:&5毒刺::poison_sting", "INV:SLIME_BALL:&5生命抵抗::poison_resist");
        regHero("timegod", "时间神", Material.CLOCK, "MAIN:IRON_SWORD:&3溯剑:DIG_SPEED:1,DAMAGE_ALL:5:time_sword", "INV:CLOCK:&3回溯之河::time_river", "INV:ENDER_PEARL:&3时瞬闪::time_flash");

        // 第 4 批 (31-40)
        regHero("qilinglong", "七玲珑", Material.POTION, "INV:POTION:&d制药-【伤】::ling_damage", "INV:POTION:&d制药-【疗】::ling_heal", "INV:POTION:&d制药-【缓】::ling_slow");
        regHero("wukong", "孙悟空", Material.STICK, "MAIN:STICK:&6金箍棒:DAMAGE_ALL:3:wukong_stick", "INV:GOLDEN_APPLE:&6火眼金睛::wukong_eyes", "INV:FEATHER:&6筋斗云::wukong_cloud", "INV:GOLD_BLOCK:&6固体金身::wukong_gold");
        regHero("buduchen", "不渡尘", Material.BLAZE_ROD, "MAIN:BLAZE_ROD:&c猩红火:DAMAGE_ALL:4,FIRE_ASPECT:1:budu_fire", "INV:FIRE_CHARGE:&c命火::budu_life", "INV:REDSTONE:&c火心罩::budu_heart");
        regHero("mechanicdream", "梦想机械师", Material.IRON_SWORD, "MAIN:IRON_SWORD:&b侧翼刀刃:DAMAGE_ALL:4:mech_blade", "INV:FISHING_ROD:&b引擎牵引::mech_rope", "INV:HEART_OF_THE_SEA:&b机械之心::mech_heart");
        regHero("reaper", "死神", Material.FLINT_AND_STEEL, "MAIN:FLINT_AND_STEEL:&0地狱-散弹枪:DAMAGE_ALL:4:reaper_gun", "INV:COAL:&0死之影::reaper_shadow", "INV:SOUL_SAND:&0死神气场::reaper_aura");
        regHero("phantom", "幻", Material.FISHING_ROD, "MAIN:FISHING_ROD:&d竿魔:DAMAGE_ALL:6:phantom_rod", "INV:ENDER_PEARL:&d幻::phantom_huan", "INV:ENDER_EYE:&d天::phantom_tian");
        regHero("dragonroar", "龙吟", Material.BOW, "MAIN:BOW:&5魄弓:PIERCING:4,ARROW_KNOCKBACK:1:dragon_bow", "INV:ARROW:&5凝箭决::dragon_arrow", "INV:DRAGON_BREATH:&5行龙诀::dragon_decree");
        regHero("tenacity", "韧", Material.NETHERITE_SWORD, "MAIN:NETHERITE_SWORD:&a韧剑:DAMAGE_ALL:3,KNOCKBACK:1:ten_sword");
        regHero("fireball", "火球滚滚", Material.BLAZE_ROD, "MAIN:BLAZE_ROD:&c滚滚吐息:FIRE_ASPECT:1:fire_breath", "INV:FIRE_CHARGE:&c滚滚火球::fire_ball", "INV:RABBIT_FOOT:&c滚滚快跑::fire_run");
        regHero("singleblade", "单刃", Material.IRON_SWORD, "MAIN:IRON_SWORD:&7弹反:DAMAGE_ALL:2:single_parry", "INV:FEATHER:&7躲闪::single_dodge", "INV:IRON_INGOT:&7沉/破::single_break");

        // 第 5 批 (41-50)
        regHero("fireweaver", "织火", Material.BLAZE_ROD, "MAIN:BLAZE_ROD:&c陨坠法杖::fire_staff", "INV:STONE:&c元素护盾::fire_shield", "INV:FLINT:&c陨坠印记::fire_mark");
        regHero("lu", "戮", Material.IRON_SWORD, "MAIN:IRON_SWORD:&8戮斩刃:DAMAGE_ALL:4,KNOCKBACK:1:lu_blade", "INV:FEATHER:&8戮踏::lu_step", "INV:REDSTONE:&8血杀意::lu_blood");
        regHero("waterwhirl", "水漩", Material.IRON_SWORD, "MAIN:IRON_SWORD:&b回漩闪:DAMAGE_ALL:3:ww_flash", "INV:PRISMARINE_SHARD:&b回漩环::ww_ring", "INV:HEART_OF_THE_SEA:&b回漩冲击::ww_impact");
        regHero("panhuang", "盘皇", Material.NETHERITE_AXE, "MAIN:NETHERITE_AXE:&6盘皇生灵斧:DAMAGE_ALL:6:ph_axe", "INV:GOLDEN_APPLE:&6信仰之力::ph_faith", "INV:NETHER_STAR:&6信仰爆发::ph_burst");
        regHero("endsprite", "末影妖精", Material.AMETHYST_SHARD, "MAIN:AMETHYST_SHARD:&d结晶体:DAMAGE_ALL:3:sprite_crystal", "INV:PRISMARINE_CRYSTALS:&d结晶凝体::sprite_condense");
        regHero("xinchao", "心潮", Material.BONE, "MAIN:BONE:&5骨杖::xing_bone", "INV:WITHER_SKELETON_SKULL:&5骨魂杖::xing_soul", "INV:ENDER_PEARL:&5占星移::xing_move", "INV:ENDER_EYE:&5占星卜::xing_divine");
        regHero("creator", "创界者", Material.STONE, "MAIN:STONE:&a创界石:DIG_SPEED:1,DAMAGE_ALL:5:create_stone", "INV:STONE:&a创界方块::create_block", "INV:SMOOTH_STONE_SLAB:&a创界平台::create_platform");
        regHero("lanyao", "蓝耀", Material.IRON_SWORD, "MAIN:IRON_SWORD:&b耀宏:DAMAGE_ALL:2,KNOCKBACK:1:yao_blade", "INV:LIGHT_BLUE_DYE:&b耀迹::yao_trace", "INV:DIAMOND:&b碎百道::yao_break");
        regHero("xiaohong", "霄鸿", Material.BOW, "MAIN:BOW:&c惊鸿:ARROW_KNOCKBACK:2,ARROW_INFINITE:1,PIERCING:4:hong_bow", "INV:NAUTILUS_SHELL:&c虹吸::hong_siphon");
        regHero("mingya", "铭牙", Material.IRON_AXE, "MAIN:IRON_AXE:&6矮人锤:DAMAGE_ALL:3:ming_hammer", "INV:WIND_CHARGE:&6实体风能::ming_wind", "INV:GUNPOWDER:&6风能收集器::ming_collector");

        // 第 6 批 (51-54)
        regHero("ghostgun", "诡枪", Material.CROSSBOW, "MAIN:CROSSBOW:&8幽鬼左轮::gun_revolver", "INV:IRON_SWORD:&8诡心匕:DAMAGE_ALL:5:gun_dagger", "INV:FEATHER:&8诡瞬::gun_dash");
        regHero("desiregod", "欲神", Material.CROSSBOW, "MAIN:CROSSBOW:&d欲之弩:PIERCING:4,ARROW_INFINITE:1:desire_crossbow", "INV:LEAD:&d欲能牵引::desire_pull", "INV:FEATHER:&d欲身行::desire_fly");
        regHero("luoling", "落灵", Material.DIAMOND_SWORD, "MAIN:DIAMOND_SWORD:&b暮靄流阴:DAMAGE_ALL:2,KNOCKBACK:4:luo_sword", "INV:SNOWBALL:&b积雨云::luo_cloud");
        regHero("wuming", "无名", Material.BOOK, "MAIN:BOOK:&6游戏菜单::wu_menu", "INV:MAP:&6个人资料::wu_profile");

        // 第 7 批 (55-61)
        regHero("astralmage", "星灵法师", Material.BLAZE_ROD, "MAIN:BLAZE_ROD:&9星灵杖:DAMAGE_ALL:2:star_staff", "INV:FIREWORK_ROCKET:&9星陨术::star_meteor", "INV:ENDER_PEARL:&9星界跃迁::star_blink");
        regHero("shadowblade", "影刃刺客", Material.IRON_SWORD, "MAIN:IRON_SWORD:&8影刃:DAMAGE_ALL:4:shadow_blade", "INV:INK_SAC:&8影遁::shadow_hide", "INV:FEATHER:&8暗影突袭::shadow_dash");
        regHero("ironmecha", "铁壁机甲", Material.IRON_AXE, "MAIN:IRON_AXE:&7动力拳套:DAMAGE_ALL:6,KNOCKBACK:2:mecha_punch", "INV:SHIELD:&7能量护盾::mecha_shield", "INV:FIRE_CHARGE:&7火箭飞拳::mecha_rocket");
        regHero("sylphranger", "森之精灵", Material.BOW, "MAIN:BOW:&a精灵长弓:ARROW_INFINITE:1,PIERCING:3:sylph_bow", "INV:GOLDEN_APPLE:&a自然之愈::sylph_heal", "INV:VINE:&a荆棘陷阱::sylph_trap");
        regHero("bloodberserker", "狂血战士", Material.NETHERITE_AXE, "MAIN:NETHERITE_AXE:&4嗜血巨斧:DAMAGE_ALL:8:blood_axe", "INV:RED_DYE:&4血之狂暴::blood_rage", "INV:SLIME_BALL:&4血之冲锋::blood_charge");
        regHero("melodyweaver", "幻音术士", Material.STICK, "MAIN:STICK:&d音律法杖:DAMAGE_ALL:1:melody_staff", "INV:NAUTILUS_SHELL:&d催眠曲::melody_sleep", "INV:TNT:&d音爆::melody_burst");
        regHero("voidwalker", "虚空行者", Material.NETHERITE_SWORD, "MAIN:NETHERITE_SWORD:&5虚空之刃:DAMAGE_ALL:5:void_blade", "INV:ENDER_EYE:&5虚空步::void_step");

        // 最终批 (62-65)
        regHero("clockwork", "发条工匠", Material.STICK, "MAIN:IRON_SWORD:&e发条扳手:DAMAGE_ALL:4:cw_wrench", "INV:REDSTONE_TORCH:&e引爆起爆器::cw_detonate", "INV:BEACON:&e过载核心::cw_core");
        regHero("bloodpriest", "渊血祭司", Material.BLAZE_ROD, "MAIN:BLAZE_ROD:&4血咒法杖::bp_staff", "INV:NETHER_WART:&4血之契约::bp_link", "INV:ROTTEN_FLESH:&4鲜血绽放::bp_bloom");
        regHero("monk", "震雷拳僧", Material.RABBIT_HIDE, "MAIN:RABBIT_HIDE:&b雷纹拳套:KNOCKBACK:1:monk_fist", "INV:FEATHER:&b闪雷步::monk_dash", "INV:TNT:&b雷霆万钧::monk_ult");
        regHero("trickster", "千面诡客", Material.PAPER, "MAIN:PAPER:&d幻象扑克:DAMAGE_ALL:2:trick_card", "INV:ENDER_PEARL:&d移形换影::trick_swap", "INV:SNOWBALL:&d镜像迷阵::trick_mirage");
    }

    private void regHero(String id, String name, Material icon, String... equipData) {
        heroes.put(id, new HeroData(name, icon, equipData));
    }

    private void registerAllSkills() {
        // 格式: ID, 类型, CD, 参数(距离/Buff等)
        // 庇护之瞬：右键短暂漂浮后瞬移15格，落地获得3秒速度II（amplifier 1 = II），CD 15s
        regSkill("guide_shelter", "GUIDE_SHELTER", 15, "15", "");
        // 疗养汤药：右键立即回复自身血量至满（+20点上限），手持持续获得生命恢复I，CD 15s
        regSkill("guide_soup", "GUIDE_SOUP", 15, "20", "");
        // 地狱魔兽技能（右键触发，CD 由引擎统一处理；具体形态分支见 executeSkill 定制 case）
        regSkill("demon_claw", "DEMON_CLAW", 14, "0", "");       // 兽护/兽怒：右键自增益+回血
        regSkill("demon_regen", "DEMON_REGEN", 20, "0", "");      // 默认回15血+抗性II/吸收I；返祖蓄力冲刺
        regSkill("demon_transform", "DEMON_TRANSFORM", 8, "0", ""); // 兽欲返祖：形态切换（不重置其他技能CD）
        regSkill("ender_punch", "TELEPORT", 1, "4", "REGENERATION:60:0");
        regSkill("ghost_dagger", "BUFF", 6, "0", "SPEED:80:0,REGENERATION:80:1");
        regSkill("ghost_dance", "TELEPORT", 0, "4", "INVISIBILITY:200:0");
        regSkill("ghost_strike", "TELEPORT", 4, "8", "DAMAGE_RESISTANCE:20:10");
        regSkill("soul_blade", "DAMAGE_AREA", 8, "5", "SPEED:60:2");
        regSkill("soul_dash", "TELEPORT", 15, "15", "SPEED:60:3");
        regSkill("soul_focus", "BUFF", 18, "0", "SLOW:100:9,LEVITATION:100:1");
        regSkill("mermaid_staff", "DAMAGE_SINGLE", 1, "4.5", "SLOW:5:0");
        regSkill("mermaid_wave", "DAMAGE_AREA", 12, "5", "ABSORPTION:20:2");
        regSkill("mermaid_song", "DAMAGE_AREA", 15, "5", "BLINDNESS:100:0,LEVITATION:100:0");
        regSkill("vanseda_axe", "HEAL", 25, "4", "REGENERATION:200:1");
        regSkill("vanseda_pact", "TOGGLE", 10, "0", "INCREASE_DAMAGE:200:0,SPEED:200:0");
        regSkill("cat_claw", "BUFF", 0, "0", "REGENERATION:20:1");
        regSkill("cat_sprint", "BUFF", 20, "0", "SPEED:300:5,REGENERATION:300:1");
        regSkill("cat_jump", "BUFF", 4, "0", "JUMP:50:2");
        regSkill("dawn_blade", "DAMAGE_AREA", 9, "10", "INCREASE_DAMAGE:100:1");
        regSkill("dawn_hide", "BUFF", 18, "0", "INVISIBILITY:200:0,SPEED:200:0");
        regSkill("dawn_flash", "TELEPORT", 6, "15", "DAMAGE_RESISTANCE:20:10");
        regSkill("mech_gun", "DAMAGE_SINGLE", 1, "4", "");
        regSkill("mech_craft_flash", "BUFF", 5, "0", "");
        regSkill("mech_repair", "HEAL", 22, "20", "");

        // 简化注册后续技能，保持代码不截断
        String[] simpleSkills = {
                "smoker_gun:BUFF:15:0:SPEED:83:3,INVISIBILITY:83:0", "smoker_ring:TELEPORT:4:5:DAMAGE_RESISTANCE:20:5",
                "wing_blade:BUFF:10:0:ABSORPTION:140:0,LEVITATION:20:30", "wing_dash:TELEPORT:15:7:ABSORPTION:80:1",
                "wing_maintain:HEAL:30:20:DAMAGE_RESISTANCE:40:10", "puppet_needle:DAMAGE_SINGLE:4:6:SPEED:100:3",
                "puppet_control:DAMAGE_AREA:18:5:INVISIBILITY:60:0", "puppet_blast:DAMAGE_AREA:0:7:SLOW:60:127",
                "sword_blade:BUFF:0:0:INCREASE_DAMAGE:20:0", "sword_move:BUFF:7:0:SPEED:100:2",
                "sword_step:TELEPORT:15:7:", "sword_adverse:TELEPORT:10:15:INCREASE_DAMAGE:100:0",
                "fighter_punch:DAMAGE_AREA:2:3:SPEED:40:2", "fighter_shield:HEAL:15:4:REGENERATION:300:1",
                "shield_atk:DAMAGE_AREA:10:7:DAMAGE_RESISTANCE:20:10", "shield_quake:DAMAGE_AREA:5:7:SLOW:60:127",
                "shield_charge:DAMAGE_AREA:10:7:DAMAGE_RESISTANCE:20:10", "ranger_jump:BUFF:10:0:JUMP:20:2",
                "ranger_spirit:HEAL:20:10:ABSORPTION:60:1", "sniper_rifle:BUFF:0:0:SLOW:100:2,NIGHT_VISION:100:0",
                "sniper_focus:BUFF:20:0:INVISIBILITY:50:0,DAMAGE_RESISTANCE:20:10", "hunter_axe:DAMAGE_AREA:10:4:FIRE_RESISTANCE:60:0",
                "hunter_crossbow:DAMAGE_SINGLE:1:3:FIRE_TICKS:60", "hunter_source:BUFF:12:0:ABSORPTION:160:0",
                "wind_bow:BUFF:0:0:REGENERATION:20:1", "wind_control:DAMAGE_SINGLE:10:3:SLOW:40:99",
                "vamp_fang:DAMAGE_SINGLE:1:3:WEAKNESS:20:0", "vamp_hunt:BUFF:20:0:DAMAGE_RESISTANCE:100:3",
                "vamp_domain:DAMAGE_AREA:20:3:SLOW:70:127", "king_punch:DAMAGE_AREA:12:9:ABSORPTION:20:0",
                "king_awaken:BUFF:6:0:SPEED:200:0,REGENERATION:200:0", "sha_sword:DAMAGE_SINGLE:23:3:LEVITATION:60:0",
                "sha_mark:DAMAGE_SINGLE:4:1:SLOW:40:0", "sha_move:TELEPORT:20:10:ABSORPTION:60:0",
                "girl_knife:BUFF:0:0:ABSORPTION:300:1", "girl_power:HEAL:15:4:ABSORPTION:300:1",
                "girl_condense:BUFF:25:0:DAMAGE_RESISTANCE:20:0", "fox_palm:DAMAGE_SINGLE:4:3:SLOW:20:0",
                "fox_pounce:TELEPORT:3:3:INCREASE_DAMAGE:20:3", "light_blade:DAMAGE_SINGLE:8:6:SLOW:40:1",
                "light_leap:BUFF:15:0:DAMAGE_RESISTANCE:60:0,SLOW:60:99", "bow_book:BUFF:30:0:INVISIBILITY:100:0",
                "bow_craft:BUFF:35:0:", "master_sword:BUFF:5:0:DAMAGE_RESISTANCE:20:10",
                "master_step:TELEPORT:18:5:SPEED:100:2", "master_hide:DAMAGE_AREA:30:4:SLOW:20:0",
                "poison_sting:DAMAGE_SINGLE:8:5:POISON:60:5,WITHER:60:5", "poison_resist:BUFF:20:0:DAMAGE_RESISTANCE:90:10",
                "time_sword:BUFF:10:0:SPEED:60:2,GLOWING:100:0", "time_river:DAMAGE_AREA:12:3:WEAKNESS:100:0",
                "time_flash:TELEPORT:20:5:GLOWING:100:0", "ling_damage:BUFF:6:0:", "ling_heal:BUFF:12:0:",
                "ling_slow:BUFF:8:0:SLOW:60:3", "wukong_stick:BUFF:6:0:INCREASE_DAMAGE:20:1,GLOWING:20:0",
                "wukong_eyes:DAMAGE_AREA:10:0:SLOW_DIGGING:100:3,GLOWING:100:0", "wukong_cloud:TELEPORT:8:5:",
                "wukong_gold:BUFF:15:0:ABSORPTION:60:10,SPEED:60:5", "budu_fire:DAMAGE_AREA:10:5:SPEED:120:1",
                "budu_life:TELEPORT:10:0:REGENERATION:60:4", "budu_heart:DAMAGE_AREA:15:3:REGENERATION:60:3",
                "mech_blade:TELEPORT:0:4:INCREASE_DAMAGE:20:0", "mech_rope:TELEPORT:18:5:SPEED:40:3",
                "mech_heart:BUFF:18:0:SLOW:60:127", "reaper_gun:DAMAGE_SINGLE:0:4:FIRE_TICKS:60",
                "reaper_shadow:BUFF:12:0:DAMAGE_RESISTANCE:100:2,INVISIBILITY:100:0", "reaper_aura:DAMAGE_AREA:18:3:SLOW:60:3",
                "phantom_rod:BUFF:0:0:", "phantom_huan:TELEPORT:27:5:ABSORPTION:100:5", "phantom_tian:TELEPORT:0:6:INVISIBILITY:160:0",
                "dragon_bow:BUFF:0:0:", "dragon_arrow:BUFF:8:0:", "dragon_decree:BUFF:25:0:SPEED:160:2",
                "ten_sword:BUFF:3:0:DAMAGE_RESISTANCE:20:2", "fire_breath:DAMAGE_AREA:3:2:FIRE_TICKS:40",
                "fire_ball:DAMAGE_SINGLE:4:5:FIRE_TICKS:100", "fire_run:BUFF:15:0:ABSORPTION:100:1",
                "single_parry:BUFF:3:0:ABSORPTION:60:0", "single_dodge:TELEPORT:4:3:ABSORPTION:60:0",
                "single_break:BUFF:0:0:", "fire_staff:DAMAGE_AREA:7:6:SLOW:60:2", "fire_shield:BUFF:10:0:DAMAGE_RESISTANCE:100:2",
                "fire_mark:DAMAGE_SINGLE:2:3:FIRE_TICKS:100", "lu_blade:DAMAGE_SINGLE:10:6:ABSORPTION:20:0",
                "lu_step:TELEPORT:12:6:SPEED:60:3", "lu_blood:BUFF:25:0:REGENERATION:200:0",
                "ww_flash:TELEPORT:8:5:DAMAGE_RESISTANCE:20:1", "ww_ring:TELEPORT:7:5:SPEED:60:2",
                "ww_impact:TELEPORT:26:5:GLOWING:100:0", "ph_axe:DAMAGE_AREA:2:6:", "ph_faith:BUFF:18:0:ABSORPTION:220:2",
                "ph_burst:BUFF:18:0:SPEED:60:10", "sprite_crystal:BUFF:0:0:DAMAGE_RESISTANCE:20:10",
                "sprite_condense:BUFF:12:0:INCREASE_DAMAGE:120:1", "xing_bone:BUFF:0:0:", "xing_soul:BUFF:0:0:",
                "xing_move:TELEPORT:10:12:DAMAGE_RESISTANCE:50:2", "xing_divine:DAMAGE_AREA:20:3:SLOW:20:3",
                "create_stone:BUFF:0:0:", "create_block:BUFF:7:0:", "create_platform:BUFF:12:0:",
                "yao_blade:DAMAGE_SINGLE:8:4:INCREASE_DAMAGE:60:0", "yao_trace:BUFF:10:0:ABSORPTION:60:1",
                "yao_break:BUFF:12:0:DAMAGE_RESISTANCE:20:10", "hong_bow:BUFF:0:0:", "hong_siphon:BUFF:10:0:DAMAGE_RESISTANCE:60:10",
                "ming_hammer:BUFF:0:0:", "ming_wind:DAMAGE_SINGLE:1:1:", "ming_collector:BUFF:14:0:",
                "gun_revolver:DAMAGE_SINGLE:0:3:", "gun_dagger:DAMAGE_SINGLE:5:5:BLINDNESS:40:0", "gun_dash:TELEPORT:7:5:SPEED:40:0",
                "desire_crossbow:BUFF:0:0:", "desire_pull:BUFF:15:0:", "desire_fly:BUFF:20:0:REGENERATION:100:4",
                "luo_sword:BUFF:0:0:", "luo_cloud:BUFF:16:0:", "wu_menu:BUFF:0:0:", "wu_profile:BUFF:0:0:",
                "star_staff:DAMAGE_SINGLE:1:3:SLOW:40:1", "star_meteor:DAMAGE_AREA:8:6:SLOW:60:2", "star_blink:TELEPORT:12:10:",
                "shadow_blade:BUFF:0:0:", "shadow_hide:BUFF:15:0:INVISIBILITY:200:0", "shadow_dash:TELEPORT:6:8:",
                "mecha_punch:BUFF:0:0:", "mecha_shield:BUFF:20:0:DAMAGE_RESISTANCE:100:0", "mecha_rocket:DAMAGE_SINGLE:10:5:SLOW:60:127",
                "sylph_bow:BUFF:0:0:", "sylph_heal:HEAL:15:10:REGENERATION:100:2", "sylph_trap:DAMAGE_AREA:8:3:SLOW:40:127",
                "blood_axe:BUFF:0:0:", "blood_rage:BUFF:12:0:INCREASE_DAMAGE:160:1,SPEED:160:1", "blood_charge:TELEPORT:8:10:",
                "melody_staff:BUFF:0:0:", "melody_sleep:DAMAGE_AREA:14:0:SLOW:80:4", "melody_burst:DAMAGE_AREA:6:5:",
                "void_blade:DAMAGE_SINGLE:3:4:", "void_step:TELEPORT:4:5:INVISIBILITY:20:0",
                "cw_wrench:BUFF:0:0:", "cw_detonate:DAMAGE_AREA:6:5:", "cw_core:BUFF:18:0:SPEED:200:1",
                "bp_staff:DAMAGE_SINGLE:2:3:SLOW:40:1", "bp_link:BUFF:14:0:", "bp_bloom:DAMAGE_AREA:12:3:",
                "monk_fist:BUFF:0:0:", "monk_dash:TELEPORT:3:4:SLOW:60:0", "monk_ult:DAMAGE_AREA:8:4:SLOW:30:9",
                "trick_card:DAMAGE_SINGLE:0:3:", "trick_swap:TELEPORT:10:6:", "trick_mirage:DAMAGE_AREA:16:4:BLINDNESS:40:0"
        };
        for (String s : simpleSkills) {
            // limit=-1 保留空字段；buffs 可能含冒号（如 SPEED:300:5），需重组而非截断
            String[] p = s.split(":", -1);
            if (p.length >= 4) {
                String buffs = p.length > 4 ? String.join(":", Arrays.copyOfRange(p, 4, p.length)) : "";
                regSkill(p[0], p[1], Integer.parseInt(p[2]), p[3], buffs);
            }
        }
    }

    private void regSkill(String id, String type, int cd, String param, String buffs) {
        skills.put(id, new SkillData(type, cd, param, buffs));
    }

    // ================= 2. GUI 菜单系统（分页） =================
    /** 每页最多 45 个英雄，第 6 行放置翻页按钮 */
    private static final int PAGE_SIZE = 45;

    private void openGUI(Player player) {
        openGUI(player, 0);
    }

    private void openGUI(Player player, int page) {
        List<String> ids = new ArrayList<>(heroes.keySet());
        int totalPages = Math.max(1, (ids.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory gui = Bukkit.createInventory(null, 54,
                ChatColor.DARK_AQUA + GUI_TITLE_ID + " (" + heroes.size() + ") 第 " + (page + 1) + "/" + totalPages + " 页");

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, ids.size());
        int slot = 0;
        for (int i = start; i < end; i++) {
            String id = ids.get(i);
            HeroData h = heroes.get(id);
            ItemStack icon = new ItemStack(h.icon);
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + h.name);
            meta.setLore(Arrays.asList(ChatColor.GRAY + "点击选择", ChatColor.DARK_GRAY + "ID: " + id));
            meta.getPersistentDataContainer().set(classKey, PersistentDataType.STRING, "sel_" + id);
            icon.setItemMeta(meta);
            gui.setItem(slot++, icon);
        }

        // 翻页按钮（带页码标签，防止越界点击）
        if (page > 0) {
            gui.setItem(45, pageButton(Material.ARROW, "&a上一页", "pg_" + (page - 1)));
        }
        if (page < totalPages - 1) {
            gui.setItem(53, pageButton(Material.ARROW, "&e下一页", "pg_" + (page + 1)));
        }
        player.openInventory(gui);
    }

    private ItemStack pageButton(Material mat, String name, String tag) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        meta.getPersistentDataContainer().set(classKey, PersistentDataType.STRING, tag);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onGUIClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (title == null || !title.contains(GUI_TITLE_ID)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String data = item.getItemMeta().getPersistentDataContainer().get(classKey, PersistentDataType.STRING);
        if (data == null) return;
        if (data.startsWith("sel_")) {
            selectClass(player, data.substring(4));
            player.closeInventory();
        } else if (data.startsWith("pg_")) {
            // 下一 tick 重新打开，避免在库存点击事件中同步开关窗口导致状态异常
            int nextPage = Integer.parseInt(data.substring(3));
            Bukkit.getScheduler().runTask(this, () -> openGUI(player, nextPage));
        }
    }

    private void selectClass(Player player, String classId) {
        HeroData hero = heroes.get(classId);
        if (hero == null) {
            player.sendMessage(ChatColor.RED + "职业不存在: " + classId);
            return;
        }

        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        // 切换职业时清理地狱魔兽被动状态（永久生命提升等），防止叠加残留
        removeDemonHealthBoost(player);
        demonAtavism.remove(player.getUniqueId());
        demonBuffEnd.remove(player.getUniqueId());
        demonVulnEnd.remove(player.getUniqueId());
        // 按玩家实际最大血量/饥饿值重置，而非硬编码 20
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        player.setHealth(maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0);
        player.setFoodLevel(20);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        states.remove(player.getUniqueId());
        playerClassMap.put(player.getUniqueId(), classId); // 记录选择，供重生/重启后恢复
        player.sendMessage(ChatColor.YELLOW + "已选择 [" + hero.name + "]！正在发放装备...");

        giveHeroEquipment(player, hero);
        if ("demon".equals(classId)) applyDemonPassives(player); // 进场被动：伤害吸收II + 永久生命提升V

        // 随机传送：从最高方块向上找可站立空气位，避免卡进方块或掉入虚空
        World world = player.getWorld();
        int x = random.nextInt(2000) - 1000, z = random.nextInt(2000) - 1000;
        int ground = findGroundLevel(world, x, z);
        if (ground < 0) {
            // 该列无合适落点（如深坑），退回玩家当前位置，保证不丢失装备后的传送失败
            player.sendMessage(ChatColor.RED + "随机传送落点无效，已留在原地。");
            return;
        }
        Location safe = new Location(world, x + 0.5, ground, z + 0.5);
        player.teleport(safe);
        player.playSound(safe, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    /** 解析英雄装备配置并放入玩家背包/装备栏（selectClass 与 restoreClass 共用） */
    private void giveHeroEquipment(Player player, HeroData hero) {
        // 解析并生成装备
        for (String eq : hero.equipData) {
            String[] p = eq.split(":");
            if (p.length < 3) continue;
            String slot = p[0], matStr = p[1], name = p[2];
            String enchants = p.length > 3 ? p[3] : "";
            // 第5段可能是技能ID，也可能是属性修饰（ATTACK_DAMAGE:x）；第6段才是技能ID
            String field5 = p.length > 4 ? p[4] : "";
            String attributes = field5.contains(";") ? field5 : "";
            String skillId = p.length > 5 ? p[5] : (!attributes.isEmpty() ? "" : field5);

            Material mat = Material.matchMaterial(matStr);
            if (mat == null || !mat.isItem()) {
                getLogger().warning("英雄的装备材质不可用，已跳过: " + matStr);
                continue;
            }
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

            // 解析自定义属性修饰（护甲加成、攻击伤害、攻击速度等）
            if (!attributes.isEmpty()) {
                for (String attrEntry : attributes.split(",")) {
                    String[] ap = attrEntry.split(":");
                    if (ap.length < 2) continue;
                    Attribute attr;
                    try {
                        attr = Attribute.valueOf(ap[0].startsWith("GENERIC_") ? ap[0] : "GENERIC_" + ap[0]);
                    } catch (IllegalArgumentException ex) {
                        getLogger().warning("未知属性名: " + ap[0] + "（装备 " + name + "）");
                        continue;
                    }
                    try {
                        double amount = Double.parseDouble(ap[1]);
                        AttributeModifier mod = new AttributeModifier(UUID.randomUUID(), "mc_" + ap[0].toLowerCase(),
                                amount, AttributeModifier.Operation.ADD_NUMBER);
                        meta.addAttributeModifier(attr, mod);
                    } catch (NumberFormatException ex) {
                        getLogger().warning("属性数值非法: " + attrEntry + "（装备 " + name + "）");
                    }
                }
            }

            // 解析附魔（对非法等级/名称做容错，避免个别配置错误导致整个技能失效）
            if (!enchants.isEmpty()) {
                for (String enc : enchants.split(",")) {
                    String[] ep = enc.split(":");
                    if (ep.length < 2) continue;
                    Enchantment e = Enchantment.getByName(ep[0]);
                    if (e == null) {
                        getLogger().warning("未知附魔名: " + ep[0] + "（装备 " + name + "）");
                        continue;
                    }
                    try {
                        meta.addEnchant(e, Integer.parseInt(ep[1]), true);
                    } catch (IllegalArgumentException ex) {
                        getLogger().warning("附魔等级非法: " + enc + "（装备 " + name + "）");
                    }
                }
            }
            // 写入技能标签
            if (!skillId.isEmpty()) {
                meta.getPersistentDataContainer().set(classKey, PersistentDataType.STRING, skillId);
            }
            // 地狱魔兽装备定制：按玩家当前形态调整数值/附魔（返祖时覆盖默认注册表数值）
            if (isDemonEquip(name)) {
                customizeDemonItem(player, name, item, meta);
            }
            item.setItemMeta(meta);

            // 装备到对应槽位
            switch (slot) {
                case "HELMET": player.getInventory().setHelmet(item); break;
                case "CHEST": player.getInventory().setChestplate(item); break;
                case "LEGS": player.getInventory().setLeggings(item); break;
                case "BOOTS": player.getInventory().setBoots(item); break;
                case "MAIN": player.getInventory().setItemInMainHand(item); break;
                case "OFF": player.getInventory().setItemInOffHand(item); break;
                case "INV": player.getInventory().addItem(item); break;
                default:
                    getLogger().warning("未知装备槽位: " + slot + "（英雄 " + hero.name + "）");
            }
        }
    }

    // ================= 3. 通用技能执行引擎 =================
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        String skillId = item.getItemMeta().getPersistentDataContainer().get(classKey, PersistentDataType.STRING);
        if (skillId == null) return;
        SkillData skill = skills.get(skillId);
        if (skill == null) {
            // 技能ID未注册（配置错误）：给出明确提示，便于排查而非静默失效
            getLogger().warning("玩家 " + player.getName() + " 触发了未注册的技能ID: " + skillId);
            return;
        }

        // 冷却检查放在 setCancelled 之前：冷却中仍拦截原物品行为（如弓箭连点），防止绕过 CD
        if (!checkCooldown(player, skillId, skill.cd)) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);

        try {
            executeSkill(player, skill);
        } catch (RuntimeException ex) {
            // 单个技能数据异常不应导致监听器报错刷屏，记录日志便于修正配置
            getLogger().warning("技能 " + skillId + "（类型 " + skill.type + "，参数 " + skill.param + "）执行失败: " + ex.getMessage());
        }

        applyBuffs(player, skill.buffs);
    }

    /** 技能效果分发；返回是否成功释放 */
    private void executeSkill(Player player, SkillData skill) {
        switch (skill.type) {
            case "DEMON_CLAW":
                demonClaw(player);
                break;
            case "DEMON_REGEN":
                demonRegen(player);
                break;
            case "DEMON_TRANSFORM":
                demonTransform(player);
                break;
            case "TELEPORT": {
                double dist = Double.parseDouble(skill.param);
                Location origin = player.getLocation();
                Location target = origin.clone().add(origin.getDirection().multiply(dist));
                World w = target.getWorld();
                int ground = findGroundLevel(w, target.getBlockX(), target.getBlockZ());
                if (ground < 0) ground = origin.getBlockY(); // 找不到落点时保持原高度
                Location land = new Location(w, target.getX() + 0.5, ground + 1, target.getZ() + 0.5);
                player.teleport(land);
                player.playSound(land, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                break;
            }
            case "HEAL": {
                double heal = Double.parseDouble(skill.param);
                AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
                player.setHealth(Math.min(player.getHealth() + heal, maxHealth));
                break;
            }
            case "GUIDE_SHELTER": {
                // 庇护之瞬：右键短暂漂浮（LEVITATION 约0.5秒）后向视线方向瞬移15格，
                // 落地后提供3秒速度II；带粒子与音效反馈
                double dist = Double.parseDouble(skill.param);
                Location origin = player.getLocation();
                player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 10, 0, false, false, false));
                player.getWorld().spawnParticle(Particle.PORTAL, origin, 30, 0.4, 0.6, 0.4, 0.1);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (!player.isOnline() || player.isDead()) return;
                    Location tgt = player.getLocation();
                    World w = tgt.getWorld();
                    Location target = tgt.clone().add(tgt.getDirection().multiply(dist));
                    int ground = findGroundLevel(w, target.getBlockX(), target.getBlockZ());
                    if (ground < 0) ground = tgt.getBlockY(); // 找不到落点时保持原高度，避免掉入虚空
                    Location land = new Location(w, target.getX() + 0.5, ground + 1, target.getZ() + 0.5);
                    player.teleport(land);
                    player.removePotionEffect(PotionEffectType.LEVITATION);
                    player.playSound(land, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    player.getWorld().spawnParticle(Particle.PORTAL, land, 30, 0.4, 0.6, 0.4, 0.1);
                    // 瞬移完成后 3 秒速度 II（amplifier 1 = II）
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, true, true));
                }, 10L);
                break;
            }
            case "GUIDE_SOUP": {
                // 疗养汤药：右键立即回复自身血量（+20点，受上限约束），
                // 手持期间持续获得生命恢复I（由 onItemHeld 监听器实现，此处补一个3秒保底窗口）
                double heal = Double.parseDouble(skill.param);
                AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
                player.setHealth(Math.min(player.getHealth() + heal, maxHealth));
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, true));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 1.0f, 1.0f);
                break;
            }
            case "DAMAGE_AREA": {
                double dmg = Double.parseDouble(skill.param);
                int range = dmg > 5 ? 5 : 3; // 简单范围推断
                Location origin = player.getLocation();
                for (Entity e : player.getNearbyEntities(range, range, range)) {
                    if (e instanceof LivingEntity && e != player) {
                        ((LivingEntity) e).damage(dmg, player);
                        if (dmg > 4) {
                            Vector dir = e.getLocation().toVector().subtract(origin.toVector());
                            if (dir.lengthSquared() > 1e-6) {
                                e.setVelocity(dir.normalize().multiply(1.5));
                            }
                        }
                    }
                }
                break;
            }
            case "DAMAGE_SINGLE": {
                double sDmg = Double.parseDouble(skill.param);
                Entity targetE = player.getTargetEntity(15);
                if (targetE instanceof LivingEntity) ((LivingEntity) targetE).damage(sDmg, player);
                break;
            }
            case "BUFF":
            case "TOGGLE":
                // 自身增益由后置 Buff 统一应用
                break;
            default:
                getLogger().warning("未知技能类型: " + skill.type);
        }
    }

    // ================= 地狱魔兽（demon）专属逻辑 =================

    /** 判断装备显示名是否属于地狱魔兽的定制品（兽皮/兽爪） */
    private static boolean isDemonEquip(String plainName) {
        return plainName != null && (plainName.contains("\u517D\u76AE") || plainName.contains("\u517D\u722A")); // 兽皮 / 兽爪
    }

    /**
     * 按玩家当前形态定制地狱魔兽装备：
     * - 返祖兽皮胸甲：火焰保护V、保护I、+3护甲（去掉弹射物保护II）
     * - 返祖兽腿：火焰保护V、爆炸保护II、保护I、+2护甲（去掉弹射物保护II）
     * - 返祖兽爪：攻击伤害+3.5，名称改为"兽爪·返祖"（供战斗监听识别形态）
     */
    private void customizeDemonItem(Player player, String name, ItemStack item, ItemMeta meta) {
        if (!demonAtavism.contains(player.getUniqueId())) return; // 初始形态直接使用注册表默认值
        if (name.contains("\u517D\u76AE")) { // 兽皮
            if (item.getType() == Material.LEATHER_CHESTPLATE) {
                meta.removeEnchant(Enchantment.PROTECTION_PROJECTILE);
            } else if (item.getType() == Material.LEATHER_LEGGINGS) {
                meta.removeEnchant(Enchantment.PROTECTION_PROJECTILE);
            }
        } else if (name.contains("\u517D\u722A")) { // 兽爪
            for (AttributeModifier mod : new ArrayList<>(meta.getAttributeModifiers(Attribute.GENERIC_ATTACK_DAMAGE))) {
                meta.removeAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, mod);
            }
            meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE,
                    new AttributeModifier(UUID.randomUUID(), "mc_attack_damage", 3.5, AttributeModifier.Operation.ADD_NUMBER));
            meta.setDisplayName(DEMON_CLAW_ATV_NAME);
        }
        item.setItemMeta(meta);
    }

    /** 兽爪右键：初始=【兽护】(发光I/迅捷I/生命恢复II 4s)+回4血；返祖=【兽怒】(自身易损5s+发光/迅捷III/力量I 4s) */
    private void demonClaw(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        healPlayer(player, 4);
        if (demonAtavism.contains(uuid)) {
            // 返祖：兽怒——自身获得5秒易伤（受到伤害+60%），期间发光/迅捷III/力量I
            applyDemonVulnerable(uuid, 100); // 易伤窗口覆盖兽怒全程并略留余量
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 80, 0, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 2, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, 80, 0, true, true));
            demonBuffEnd.put(uuid, now + 4000L);
            player.sendMessage(ChatColor.DARK_RED + "\u517D\u6012\uff01\u4f64\u8eab\u7684\u91ce\u517d\u672c\u80fd\u82cf\u9192\u4e86\uff01"); // 兽怒！你自身的野兽本能苏醒了！
        } else {
            // 初始：兽护——发光I/迅捷I/生命恢复II 持续4s
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 80, 0, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 0, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 1, true, true));
            demonBuffEnd.put(uuid, now + 4000L);
            player.sendMessage(ChatColor.GREEN + "\u83b7\u5f97\u3010\u517D\u62a4\u3011\uff0c4\u79d2\u5185\u7528\u517D\u722A\u547d\u4e2d\u53ef\u5237\u65b0\u5e76\u56de\u8840\uff01"); // 获得【兽护】
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 1.0f, 0.8f);
    }

    /** 兽之再生：初始=回15滴血+10s抗性II+15s伤害吸收I；返祖=下潜定身蓄力2s后直线冲刺至多14格 */
    private void demonRegen(Player player) {
        UUID uuid = player.getUniqueId();
        if (demonAtavism.contains(uuid)) {
            startDemonDash(player);
            return;
        }
        healPlayer(player, 15);
        player.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 200, 1, true, true));  // 10s 抗性II
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 300, 0, true, true));          // 15s 吸收I
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.8f);
        player.sendMessage(ChatColor.GREEN + "\u517D\u4e4b\u518d\u751f\uff1a\u6062\u590d15\u70b9\u751f\u547d\uff01");
    }

    /**
     * 返祖·兽之再生：快速下降一格并原地定身蓄力2s（期间对附近小范围单位持续造成2点伤害，
     * 自身获得抗性提升II），蓄力结束朝面向直线冲刺至多14格，点燃冲撞单位并随机造成6/4/2点伤害。
     */
    private void startDemonDash(Player player) {
        UUID uuid = player.getUniqueId();
        Location start = player.getLocation().clone();
        // 快速下降一格（带安全校验，防止卡进方块）
        Location down = start.clone().add(0, -1, 0);
        Block landing = down.getBlock();
        if (!landing.isPassable() && !landing.getType().isAir()) {
            down = start.clone();
        }
        player.teleport(down);
        player.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 100, 1, true, true)); // 蓄力期抗性II
        player.getWorld().spawnParticle(Particle.SMOKE_NORMAL, player.getLocation(), 40, 0.8, 0.3, 0.8, 0.05);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f);
        player.setVelocity(new Vector(0, 0.05, 0)); // 轻微上浮抵消坠落感，视觉上"定身"
        final Location chargeLoc = player.getLocation().clone();
        // 蓄力2秒：每10tick对小范围(2.5格)敌人造成2点伤害；40tick后自动停止（与冲刺同时结束）
        final int[] chargeTicks = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (!player.isOnline() || player.isDead() || chargeTicks[0]++ >= 4) { task.cancel(); return; }
            for (Entity e : player.getNearbyEntities(2.5, 2.5, 2.5)) {
                if (e instanceof LivingEntity && !(e instanceof Player && ((Player) e).getGameMode() == GameMode.CREATIVE)) {
                    ((LivingEntity) e).damage(2.0, player);
                }
            }
            player.getWorld().spawnParticle(Particle.ENCHANTMENT_TABLE, player.getLocation(), 20, 0.6, 0.8, 0.6, 0.1);
        }, 0L, 10L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || player.isDead()) return;
            // 冲刺：沿蓄力开始时的面向逐格推进，命中实体则停下结算
            Vector dir = chargeLoc.getDirection();
            if (dir.getX() == 0 && dir.getZ() == 0) dir = chargeLoc.getDirection().setY(0); // 纯俯视时取水平分量
            if (dir.setX(0).lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
            dir.setY(0).normalize();
            demonDashActive.add(uuid);
            Location dashFrom = player.getLocation().clone();
            boolean hitSomething = false;
            for (int step = 1; step <= 14; step++) {
                Location probe = dashFrom.clone().add(dir.clone().multiply(step));
                Entity hit = null;
                for (Entity e : getEntitiesNear(probe.getWorld(), probe, 1.2)) {
                    if (e instanceof LivingEntity && e != player) { hit = e; break; }
                }
                if (hit != null) {
                    player.teleport(probe.clone().subtract(dir.clone().multiply(0.5)));
                    settleDemonDashHit(player, (LivingEntity) hit);
                    hitSomething = true;
                    break;
                }
            }
            if (!hitSomething) {
                Location end = dashFrom.clone().add(dir.clone().multiply(14));
                int ground = findGroundLevel(end.getWorld(), end.getBlockX(), end.getBlockZ());
                if (ground >= 0) end.setY(Math.max(ground, dashFrom.getBlockY()));
                player.teleport(end);
                Bukkit.getScheduler().runTaskLater(MiniClassPlugin.this, () -> demonDashActive.remove(uuid), 10L);
            }
            player.getWorld().spawnParticle(Particle.SMOKE_NORMAL, player.getLocation(), 25, 0.3, 0.3, 0.3, 0.05);
        }, 40L);
    }

    /** 查询以 loc 为中心 radius 范围内的实体（跨世界安全） */
    private List<Entity> getEntitiesNear(World world, Location loc, double radius) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(loc, radius, radius, radius)) {
            out.add(e);
        }
        return out;
    }

    /** 冲刺命中结算：点燃目标 + 随机 6/4/2 点伤害（由 onEntityDamage 拦截首击完成） */
    private void settleDemonDashHit(Player player, LivingEntity target) {
        UUID uuid = player.getUniqueId();
        demonDashActive.remove(uuid);
        target.setFireTicks(40);
        int[] pool = {6, 4, 2};
        double dmg = pool[random.nextInt(pool.length)];
        target.damage(dmg, player);
        Vector kb = target.getLocation().toVector().subtract(player.getLocation().toVector());
        if (kb.lengthSquared() > 1e-6) {
            target.setVelocity(kb.normalize().multiply(1.5).setY(0.5));
        }
        player.getWorld().spawnParticle(Particle.EXPLOSION_NORMAL, target.getLocation(), 6, 0.3, 0.3, 0.3, 0.05);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.7f);
    }

    /** 兽欲返祖：切换形态，重新发放武器/防具（不重置其他技能CD），两种形态均回2血 */
    private void demonTransform(Player player) {
        UUID uuid = player.getUniqueId();
        boolean toAtavism = !demonAtavism.contains(uuid);
        if (toAtavism) demonAtavism.add(uuid); else demonAtavism.remove(uuid);
        healPlayer(player, 2);
        HeroData hero = heroes.get("demon");
        if (hero != null) giveHeroEquipment(player, hero); // 覆盖式发放对应形态装备
        if (toAtavism) {
            player.sendMessage(ChatColor.DARK_RED + "\u8fdb\u5165\u8fd4\u7956\u5f62\u6001\uff01\u517D\u722A\u4f24\u5bb3\u63d0\u5347\uff0c\u517D\u4e4b\u518d\u751f\u53d8\u4e3a\u84c4\u529b\u51b2\u523a\uff01");
        } else {
            // 切回初始：获得伤害吸收II（8点黄血）
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 1, true, true));
            player.sendMessage(ChatColor.GREEN + "\u56de\u5230\u521d\u59cb\u5f62\u6001\uff0c\u83b7\u5f97\u4f24\u5bb3\u5438\u6536 II\uff01");
        }
        player.playSound(player.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.2f, 0.6f);
        player.getWorld().spawnParticle(Particle.SMOKE_LARGE, player.getLocation(), 30, 0.5, 0.8, 0.5, 0.05);
    }

    /** 回血（受最大血量上限约束） */
    private void healPlayer(Player player, double amount) {
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double max = attr != null ? attr.getValue() : 20.0;
        player.setHealth(Math.min(player.getHealth() + amount, max));
    }

    /** 幂等添加永久生命提升V（+10红心上限）；1.20.1 API 使用 getName() 标识修饰符 */
    private void ensureDemonHealthBoost(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) return;
        for (AttributeModifier m : attr.getModifiers()) {
            if ("demon_health_boost".equals(m.getName())) return; // 已存在，避免重复叠加
        }
        attr.addModifier(new AttributeModifier(HEALTH_BOOST_DEMON, "demon_health_boost", 10.0,
                AttributeModifier.Operation.ADD_NUMBER));
    }

    private void removeDemonHealthBoost(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) return;
        for (AttributeModifier m : attr.getModifiers()) {
            if ("demon_health_boost".equals(m.getName())) { attr.removeModifier(m); return; }
        }
    }

    // ---- 返祖【兽怒】易伤（受伤+60%）状态管理 ----
    private void applyDemonVulnerable(UUID uuid, int ticks) {
        demonVulnEnd.put(uuid, System.currentTimeMillis() + ticks * 50L);
    }

    private boolean isDemonVulnerable(UUID uuid) {
        Long end = demonVulnEnd.get(uuid);
        if (end == null) return false;
        if (System.currentTimeMillis() > end) { demonVulnEnd.remove(uuid); return false; }
        return true;
    }

    /** 进场被动：伤害吸收II（8点黄血）+ 永久生命提升V（+10红心，通过属性修饰符实现） */
    private void applyDemonPassives(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 1, true, true));
        ensureDemonHealthBoost(player);
    }

    /** 玩家死亡时移除永久生命提升修饰符，防止跨职业/重生后残留 */
    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        removeDemonHealthBoost(event.getEntity());
        demonBuffEnd.remove(event.getEntity().getUniqueId());
        demonVulnEnd.remove(event.getEntity().getUniqueId());
        demonDashActive.remove(event.getEntity().getUniqueId());
    }

    /**
     * 地狱魔兽被动：火焰燃烧时间 x2.5（原版烧地岩浆同样生效）。
     * 通过持续补充火tick实现：玩家着火期间每tick把剩余火烧延长至基准的2.5倍。
     */
    @EventHandler
    public void onDemonFire(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FIRE
                && event.getCause() != EntityDamageEvent.DamageCause.FIRE_TICK
                && event.getCause() != EntityDamageEvent.DamageCause.LAVA) return;
        Player p = (Player) event.getEntity();
        if (!"demon".equals(playerClassMap.get(p.getUniqueId()))) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && !p.isDead() && p.getFireTicks() > 0) {
                p.setFireTicks((int) (p.getFireTicks() * 2.5));
            }
        }, 1L);
    }

    /** 地狱魔兽被动：30%爆炸击退抗性（在击退生效后按反方向抵消30%速度） */
    @EventHandler(ignoreCancelled = true)
    public void onDemonExplosionKB(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!(event.getDamager() instanceof Creeper) && !(event.getDamager() instanceof TNTPrimed)) return;
        Player p = (Player) event.getEntity();
        if (!"demon".equals(playerClassMap.get(p.getUniqueId()))) return;
        Vector v = p.getVelocity();
        p.setVelocity(v.multiply(0.7));
    }

    /**
     * 地狱魔兽·兽爪战斗被动：
     * - 1.35 暴击倍率（返祖形态下由冲刺结算外的普攻也享受；此处对两形态统一按设定实现于返祖）
     * - 初始形态：4s【兽护】窗口内命中 → 刷新3s并回4血
     * - 返祖形态：4s【兽怒】窗口内命中 → 短暂点燃目标并刷新3s
     */
    private void handleDemonClawHit(Player player, LivingEntity target) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long buffEnd = demonBuffEnd.get(uuid);
        boolean inBuff = buffEnd != null && now < buffEnd;
        // 内置刷新CD：1秒内不重复刷新，防止多段伤害高频触发
        Long last = demonClawRefresh.get(uuid);
        if (last != null && now - last < 1000) return;
        if (!inBuff) return;
        demonClawRefresh.put(uuid, now);
        demonBuffEnd.put(uuid, now + 3000L); // 刷新3秒持续时间
        if (demonAtavism.contains(uuid)) {
            target.setFireTicks(Math.max(target.getFireTicks(), 20)); // 短暂点燃约1秒
        } else {
            healPlayer(player, 4); // 兽护命中回血
        }
    }

    /** 解析并应用 "类型:时长:等级" 逗号分隔的药水效果列表 */
    private void applyBuffs(LivingEntity entity, String buffs) {
        if (buffs == null || buffs.isEmpty()) return;
        for (String b : buffs.split(",")) {
            String[] bp = b.split(":");
            if (bp.length < 3) continue;
            PotionEffectType type = PotionEffectType.getByName(bp[0]);
            if (type == null) {
                getLogger().warning("未知药水效果: " + bp[0]);
                continue;
            }
            try {
                int duration = Integer.parseInt(bp[1]);
                int amplifier = Integer.parseInt(bp[2]);
                entity.addPotionEffect(new PotionEffect(type, duration, amplifier, true, true));
            } catch (NumberFormatException ex) {
                getLogger().warning("Buff 参数非法: " + b);
            }
        }
    }

    /**
     * 疗养汤药"手持持续获得生命恢复I"：切换快捷栏物品时检测，
     * 若新持有物品为疗养汤药则刷新一个2秒的生命恢复I窗口（循环续杯），
     * 切走则自然过期；同时兜底处理死亡/换职业等状态变化。
     */
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack next = player.getInventory().getItem(event.getNewSlot());
        if (next != null && displayName(next).contains("疗养汤药")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 40, 0, true, true));
        }
    }

    /**
     * 在给定 (x,z) 列中自上而下寻找可站立的地面高度：
     * 要求脚下为实体方块、其上两格为空气且不含液体；找不到返回 -1。
     */
    private int findGroundLevel(World world, int x, int z) {
        for (int y = world.getMaxHeight() - 4; y > world.getMinHeight(); y--) {
            Block below = world.getBlockAt(x, y, z);
            if (below.isLiquid() || below.getType().isAir()) continue;
            Block feet = below.getRelative(0, 1, 0);
            Block head = below.getRelative(0, 2, 0);
            if ((feet.getType().isAir() || feet.isPassable())
                    && (head.getType().isAir() || head.isPassable())
                    && !feet.isLiquid() && !head.isLiquid()) {
                return y + 1;
            }
        }
        return -1;
    }

    /** 读取主手物品的显示名（无 Meta 或无名时返回空串，避免 NPE） */
    private static String displayName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return "";
        String name = item.getItemMeta().getDisplayName();
        return name == null ? "" : name;
    }

    // ================= 4. 被动与战斗监听 =================
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow)) return;
        Arrow arrow = (Arrow) event.getEntity();
        if (!(arrow.getShooter() instanceof Player)) return;
        Player shooter = (Player) arrow.getShooter();
        if (!(event.getHitEntity() instanceof LivingEntity)) return;
        LivingEntity target = (LivingEntity) event.getHitEntity();

        // 指引者被动：天弓命中击退 + 点燃（1.5s 内置冷却）
        if (displayName(shooter.getInventory().getItemInMainHand()).contains("指引天弓")) {
            UUID uuid = shooter.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = passiveCD.get(uuid);
            if (last != null && now - last < 1500) return;
            passiveCD.put(uuid, now);
            Vector kb = target.getLocation().toVector().subtract(shooter.getLocation().toVector());
            if (kb.lengthSquared() > 1e-6) {
                target.setVelocity(kb.normalize().multiply(1.2).setY(0.6));
            }
            target.setFireTicks(30); // 点燃约 1.5 秒（30 tick）
            // 射手定位：命中附带"标记"效果（发光 + 缓慢，持续 4 秒），便于团队集火
            if (target instanceof Player) {
                Player marked = (Player) target;
                marked.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 80, 0, true, false, false));
                marked.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 80, 0, true, true));
                marked.sendMessage(ChatColor.GOLD + "你被指引者标记了！");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // 地狱魔兽·返祖【兽怒】易伤：自身作为受击方且处于易伤窗口时，受到伤害+60%
        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            if ("demon".equals(playerClassMap.get(victim.getUniqueId())) && isDemonVulnerable(victim.getUniqueId())) {
                event.setDamage(event.getDamage() * 1.6);
            }
        }

        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;
        Player player = (Player) event.getDamager();
        LivingEntity target = (LivingEntity) event.getEntity();
        String weaponName = displayName(player.getInventory().getItemInMainHand());

        // 地狱魔兽·兽爪（武器本身带技能标签，显示名可能为空/返祖名）：按PDC识别，不依赖名称
        ItemStack held = player.getInventory().getItemInMainHand();
        String heldSkill = held.hasItemMeta() && held.getItemMeta().getPersistentDataContainer().has(classKey, PersistentDataType.STRING)
                ? held.getItemMeta().getPersistentDataContainer().get(classKey, PersistentDataType.STRING) : "";
        if ("demon_claw".equals(heldSkill)) {
            // 返祖形态：1.35 暴击倍率
            if (demonAtavism.contains(player.getUniqueId())) {
                event.setDamage(event.getDamage() * 1.35);
            }
            handleDemonClawHit(player, target);
            return;
        }

        if (weaponName.isEmpty()) return;

        // 影刃刺客背刺：目标没有视线能看到攻击者时伤害翻倍
        if (weaponName.contains("影刃")) {
            if (!target.hasLineOfSight(player)) event.setDamage(event.getDamage() * 2.0);
        }
        // 狂血战士低血吸血：按目标最大血量比例回复，避免硬编码上限
        if (weaponName.contains("嗜血巨斧") && player.getHealth() < 10.0) {
            AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
            player.setHealth(Math.min(player.getHealth() + 2.0, maxHealth));
        }
    }

    private boolean checkCooldown(Player player, String skillId, int seconds) {
        if (seconds <= 0) return true;
        String key = player.getUniqueId() + "_" + skillId;
        long now = System.currentTimeMillis();
        long cdEnd = cooldowns.getOrDefault(key, 0L) + seconds * 1000L;
        if (cdEnd > now) {
            player.sendMessage(ChatColor.RED + "冷却中: " + String.format("%.1f", (cdEnd - now) / 1000.0) + "s");
            return false;
        }
        cooldowns.put(key, now);
        return true;
    }

    // 内部数据类（不可变、静态，避免持有外部插件实例引用）
    private static final class HeroData {
        final String name;
        final Material icon;
        final String[] equipData;
        HeroData(String n, Material i, String[] e) { name = n; icon = i; equipData = e; }
    }

    private static final class SkillData {
        final String type;
        final String param;
        final String buffs;
        final int cd;
        SkillData(String t, int c, String p, String b) { type = t; cd = c; param = p; buffs = b; }
    }
}