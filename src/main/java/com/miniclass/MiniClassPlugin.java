package com.miniclass;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

public class MiniClassPlugin extends JavaPlugin implements Listener {

    private NamespacedKey classKey;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> passiveCD = new HashMap<>();
    private final Map<UUID, String> states = new HashMap<>();

    // 内存数据注册表
    private final Map<String, HeroData> heroes = new HashMap<>();
    private final Map<String, SkillData> skills = new HashMap<>();

    @Override
    public void onEnable() {
        classKey = new NamespacedKey(this, "mc_item");
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("miniclass").setExecutor(this);

        // 注册所有 65 个英雄和技能数据 (极限压缩硬编码)
        registerAllHeroes();
        registerAllSkills();

        getLogger().info("职业缩小版终极版已加载！共 " + heroes.size() + " 个英雄。");
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (sender instanceof Player) openGUI((Player) sender);
        return true;
    }

    // ================= 1. 数据注册引擎 (65个英雄硬编码区) =================
    private void registerAllHeroes() {
        // 格式: ID, 名称, 图标材质, 装备数据(槽位:材质:名称:附魔:技能ID)
        regHero("guide", "指引者", Material.IRON_HELMET,
                "HELMET:IRON_HELMET:&e指引战盔:WATER_BREATHING:1",
                "CHEST:IRON_CHESTPLATE:&e指引战甲:PROJECTILE_PROTECTION:2",
                "BOOTS:LEATHER_BOOTS:&e指引战靴:FALL_PROTECTION:10",
                "MAIN:BOW:&6指引天弓:ARROW_DAMAGE:1,ARROW_INFINITE:1",
                "INV:IRON_SWORD:&c指引匕首:LOOT_BONUS_BLOCKS:1",
                "INV:ENDER_PEARL:&6庇护之瞬::guide_shelter",
                "INV:HONEY_BOTTLE:&b疗养汤药::guide_soup");

        regHero("demon", "地狱魔兽", Material.NETHERITE_SCRAP,
                "CHEST:NETHERITE_CHESTPLATE:&c兽皮:FIRE_PROTECTION:5,PROJECTILE_PROTECTION:2",
                "MAIN:IRON_AXE:&c兽爪:DAMAGE_ALL:2:demon_claw",
                "INV:GOLDEN_APPLE:&c兽之再生::demon_regen",
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
        regHero("sylphranger", "森之精灵", Material.BOW, "MAIN:BOW:&a精灵长弓:ARROW_INFINITE:1,PIERCING:3: sylph_bow", "INV:GOLDEN_APPLE:&a自然之愈::sylph_heal", "INV:VINE:&a荆棘陷阱::sylph_trap");
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
        regSkill("guide_shelter", "TELEPORT", 15, "15", "SPEED:60:1");
        regSkill("guide_soup", "HEAL", 15, "20", "REGENERATION:60:0");
        regSkill("demon_claw", "HEAL", 14, "4", "ABSORPTION:80:0,SPEED:80:0");
        regSkill("demon_regen", "HEAL", 20, "15", "DAMAGE_RESISTANCE:200:1");
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
            String[] p = s.split(":");
            if (p.length >= 4) regSkill(p[0], p[1], Integer.parseInt(p[2]), p[3], p.length > 4 ? p[4] : "");
        }
    }

    private void regSkill(String id, String type, int cd, String param, String buffs) {
        skills.put(id, new SkillData(type, cd, param, buffs));
    }

    // ================= 2. GUI 菜单系统 =================
    private void openGUI(Player player) {
        int size = ((heroes.size() / 9) + 1) * 9;
        if (size > 54) size = 54; // 限制最大页数，实际可分页
        Inventory gui = Bukkit.createInventory(null, size, ChatColor.DARK_AQUA + "选择职业 (" + heroes.size() + ")");
        int slot = 0;
        for (Map.Entry<String, HeroData> entry : heroes.entrySet()) {
            if (slot >= size) break;
            HeroData h = entry.getValue();
            ItemStack icon = new ItemStack(h.icon);
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + h.name);
            meta.setLore(Arrays.asList(ChatColor.GRAY + "点击选择", ChatColor.DARK_GRAY + "ID: " + entry.getKey()));
            meta.getPersistentDataContainer().set(classKey, PersistentDataType.STRING, "sel_" + entry.getKey());
            icon.setItemMeta(meta);
            gui.setItem(slot++, icon);
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onGUIClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().contains("选择职业")) return;
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String data = item.getItemMeta().getPersistentDataContainer().get(classKey, PersistentDataType.STRING);
        if (data != null && data.startsWith("sel_")) {
            selectClass(player, data.replace("sel_", ""));
            player.closeInventory();
        }
    }

    private void selectClass(Player player, String classId) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        states.remove(player.getUniqueId());

        HeroData hero = heroes.get(classId);
        if (hero == null) return;
        player.sendMessage(ChatColor.YELLOW + "已选择 [" + hero.name + "]！正在发放装备...");

        // 解析并生成装备
        for (String eq : hero.equipData) {
            String[] p = eq.split(":");
            if (p.length < 3) continue;
            String slot = p[0], matStr = p[1], name = p[2];
            String enchants = p.length > 3 ? p[3] : "";
            String skillId = p.length > 4 ? p[4] : "";

            Material mat = Material.matchMaterial(matStr);
            if (mat == null) mat = Material.STONE;
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

            // 解析附魔
            if (!enchants.isEmpty()) {
                for (String enc : enchants.split(",")) {
                    String[] ep = enc.split(":");
                    Enchantment e = Enchantment.getByName(ep[0]);
                    if (e != null) meta.addEnchant(e, Integer.parseInt(ep[1]), true);
                }
            }
            // 写入技能标签
            if (!skillId.isEmpty()) {
                meta.getPersistentDataContainer().set(classKey, PersistentDataType.STRING, skillId);
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
            }
        }

        // 随机传送
        World world = player.getWorld();
        Random rand = new Random();
        int x = rand.nextInt(2000) - 1000, z = rand.nextInt(2000) - 1000;
        int y = Math.max(65, world.getHighestBlockYAt(x, z));
        player.teleport(new Location(world, x + 0.5, y + 1, z + 0.5));
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    // ================= 3. 通用技能执行引擎 =================
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        String skillId = item.getItemMeta().getPersistentDataContainer().get(classKey, PersistentDataType.STRING);
        if (skillId == null || !skills.containsKey(skillId)) return;

        event.setCancelled(true);
        SkillData skill = skills.get(skillId);
        if (!checkCooldown(player, skillId, skill.cd)) return;

        // 通用技能逻辑分发
        switch (skill.type) {
            case "TELEPORT":
                int dist = Integer.parseInt(skill.param);
                Location target = player.getLocation().add(player.getLocation().getDirection().multiply(dist));
                target.setY(player.getWorld().getHighestBlockYAt(target.getBlockX(), target.getBlockZ()) + 1);
                player.teleport(target);
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                break;
            case "HEAL":
                double heal = Double.parseDouble(skill.param);
                player.setHealth(Math.min(player.getHealth() + heal, player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
                break;
            case "DAMAGE_AREA":
                double dmg = Double.parseDouble(skill.param);
                int range = dmg > 5 ? 5 : 3; // 简单范围推断
                for (Entity e : player.getNearbyEntities(range, range, range)) {
                    if (e instanceof LivingEntity && e != player) {
                        ((LivingEntity) e).damage(dmg, player);
                        if (skill.param.contains("击退") || dmg > 4) {
                            e.setVelocity(e.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(1.5));
                        }
                    }
                }
                break;
            case "DAMAGE_SINGLE":
                double sDmg = Double.parseDouble(skill.param);
                Entity targetE = player.getTargetEntity(15);
                if (targetE instanceof LivingEntity) ((LivingEntity) targetE).damage(sDmg, player);
                break;
            case "BUFF":
            case "TOGGLE":
                // 给予自身 Buff
                break;
        }

        // 应用后置 Buff
        if (!skill.buffs.isEmpty()) {
            for (String b : skill.buffs.split(",")) {
                String[] bp = b.split(":");
                if (bp.length >= 3) {
                    PotionEffectType type = PotionEffectType.getByName(bp[0]);
                    if (type != null) {
                        player.addPotionEffect(new PotionEffect(type, Integer.parseInt(bp[1]), Integer.parseInt(bp[2])));
                    }
                }
            }
        }
    }

    // ================= 4. 被动与战斗监听 =================
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow)) return;
        Arrow arrow = (Arrow) event.getEntity();
        if (!(arrow.getShooter() instanceof Player)) return;
        Player shooter = (Player) arrow.getShooter();
        if (event.getHitEntity() == null || !(event.getHitEntity() instanceof LivingEntity)) return;
        LivingEntity target = (LivingEntity) event.getHitEntity();

        ItemStack mainHand = shooter.getInventory().getItemInMainHand();
        if (mainHand.hasItemMeta() && mainHand.getItemMeta().getDisplayName().contains("指引天弓")) {
            UUID uuid = shooter.getUniqueId();
            long now = System.currentTimeMillis();
            if (now - passiveCD.getOrDefault(uuid, 0L) < 1500) return;
            passiveCD.put(uuid, now);
            Vector kb = target.getLocation().toVector().subtract(shooter.getLocation().toVector()).normalize();
            target.setVelocity(kb.multiply(1.2).setY(0.6));
            target.setFireTicks(30);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();
        ItemStack weapon = player.getInventory().getItemInMainHand();

        // 影刃刺客背刺
        if (weapon.hasItemMeta() && weapon.getItemMeta().getDisplayName().contains("影刃")) {
            if (event.getEntity() instanceof LivingEntity) {
                LivingEntity target = (LivingEntity) event.getEntity();
                if (!target.hasLineOfSight(player)) event.setDamage(event.getDamage() * 2.0);
            }
        }
        // 狂血战士低血吸血
        if (weapon.hasItemMeta() && weapon.getItemMeta().getDisplayName().contains("嗜血巨斧")) {
            if (player.getHealth() < 10.0) player.setHealth(Math.min(player.getHealth() + 2.0, 20.0));
        }
    }

    private boolean checkCooldown(Player player, String skillId, int seconds) {
        if (seconds == 0) return true;
        String key = player.getUniqueId() + "_" + skillId;
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(key, 0L);
        long left = (last + (seconds * 1000)) - now;
        if (left > 0) {
            player.sendMessage(ChatColor.RED + "冷却中: " + (left / 1000.0) + "s");
            return false;
        }
        cooldowns.put(key, now);
        return true;
    }

    // 内部数据类
    private class HeroData {
        String name; Material icon; String[] equipData;
        HeroData(String n, Material i, String[] e) { name = n; icon = i; equipData = e; }
    }
    private class SkillData {
        String type, param, buffs; int cd;
        SkillData(String t, int c, String p, String b) { type = t; cd = c; param = p; buffs = b; }
    }
}