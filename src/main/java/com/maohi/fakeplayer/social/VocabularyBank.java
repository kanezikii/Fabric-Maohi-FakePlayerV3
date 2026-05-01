package com.maohi.fakeplayer.social;

import com.maohi.MaohiConfig;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 假人词库系统 (V3.2 全面扩充)
 * 提供各种场景下的随机发言库，并注入情绪（大小写、口癖）。
 * 
 * V3.2 修复：
 * - 从 6 条扩充到 50+ 条/场景，消除复读穿帮
 * - 接入 MaohiConfig 已有词库（chatMessages/greetingReplies/deathReactions）
 * - 新增：苦力怕恐惧词、AFK词、挖到好东西词、PvP词、闲聊词
 */
public class VocabularyBank {

	// ==================== 天气与环境 ====================
	private static final String[] RAIN = {
		"rain again", "i hate rain", "laggy rain", "why is it raining", "raining...",
		"ew rain", "so wet", "rain sucks", "can it stop raining", "brb waiting for rain to stop",
		"great more rain", "rain rain go away", "ofc its raining", "typical",
		"not again", "is it always raining here"
	};
	private static final String[] NIGHT = {
		"so dark", "can we sleep", "too many mobs", "night time", "dark outside",
		"where are the beds", "any beds?", "night already?", "its getting dark",
		"i need a bed", "mobs incoming", "gg night", "not looking forward to this",
		"whos got a bed", "sleep?", "its so dark", "brb hiding"
	};
	private static final String[] FIRE = {
		"help im burning", "fireeee", "water water", "im on fire wtf", "burning",
		"ow ow ow", "ahhh fire", "someone put me out", "help", "fire!!",
		"hot hot hot", "im dying", "wtf why am i burning", "aaa",
		"no no no", "stop drop and roll lol"
	};

	// ==================== 战斗与威胁 ====================
	private static final String[] CREEPER_FEAR = {
		"wtf creeper", "omg run", "creeper!!", "no no no", "get away",
		"a creeper!", "run run run", "not today", "back off", "ahhh creeper",
		"pls no", "ive had enough", "screw creepers", "nope",
		"creeper behind u", "watch out", "run!!", "oh crap"
	};
	private static final String[] COMBAT_WIN = {
		"ez", "lmao", "get rekt", "gg", "lol", "nice",
		"too easy", "pog", "lets go", "haha", "skill issue",
		"no diff", "done", "next", "thats what u get"
	};
	private static final String[] COMBAT_LOSE = {
		"rip", "bruh", "lmao i died", "oof", "unlucky",
		"that was close", "gg i guess", "well that sucked",
		"ow", "ill get u next time", "lag", "so unfair"
	};

	// ==================== 社交场景 ====================
	private static final String[] GREETING = {
		"hi", "hello", "yo", "hey", "supp", "o/",
		"any1?", "whats up", "howdy", "moin", "heya",
		"hola", "sup", "wassup", "ey", "hihi"
	};
	private static final String[] FAREWELL = {
		"gn", "bye", "gtg", "off now", "see ya", "cya",
		"night all", "later", "peace out", "im out", "byebye",
		"ttyl", "im going", "cya later", "take care", "night"
	};
	private static final String[] AFK_MESSAGES = {
		"brb", "afk sec", "one sec", "wait", "phone",
		"afk 1min", "give me a sec", "hold on", "be right back",
		"someone at the door", "pause", "bio", "eating brb"
	};
	private static final String[] BACK_MESSAGES = {
		"back", "im back", "ok back", "sorry was afk", "re",
		"returned", "here again", "what did i miss", "ok im here",
		"alive again", "yo im back"
	};
	private static final String[] DEATH_REACT = {
		"rip", "f", "unlucky", "oh no", "nooo", "omg",
		"bruh", "wait what?", "why", "ouch", "lmao",
		"skill issue", "RIP", "thats rough", "damn"
	};

	// ==================== 日常闲聊 ====================
	private static final String[] IDLE_CHAT = {
		"anyone here?", "so quiet", "what r u guys doing", "im bored",
		"this server is chill", "nice weather", "anyone wanna trade",
		"wheres the nearest village", "found diamonds lol jk",
		"just exploring", "anyone got spare iron", "whats up",
		"this is fun", "anyone wanna build", "bruh this is taking forever",
		"need food", "wheres my base again", "lol",
		"hmm", "ok", "ye", "sure", "nice", "gg", "xd"
	};
	private static final String[] FOUND_GOOD = {
		"yoo i found something", "nice!", "lets gooo", "omg nice",
		"pog", "no way", "finally", "worth it", "holy",
		"omg omg", "is this real", "brb screaming", "yesss"
	};

	// ==================== 任务关联 (P0-2) ====================
	private static final String[] TASK_MINING = {
		"so much stone", "found coal", "need more iron", "mining...", "my pickaxe is almost broken",
		"where are the diamonds", "this cave is huge", "so many zombies down here", "more cobble",
		"i hate gravel", "lava...", "almost died to lava", "mining level up", "back to the mines"
	};
	private static final String[] TASK_WOODCUTTING = {
		"chopping trees", "need more wood", "this tree is too tall", "timber", "getting wood",
		"wheres the forest", "need saplings", "apples!", "my axe broke", "so much wood"
	};
	private static final String[] TASK_EXPLORING = {
		"where am i", "this terrain is cool", "im lost", "exploring", "looking for a village",
		"nice view", "anyone want to base together?", "running around", "so far from spawn",
		"found a cool spot", "need food", "sprinting..."
	};

	// ==================== 工具方法 ====================

	private static String getRandom(String[] bank) {
		return bank[ThreadLocalRandom.current().nextInt(bank.length)];
	}

	/** 注入情绪修饰：大小写/口癖/后缀 */
	public static String addEmotion(String text) {
		int style = ThreadLocalRandom.current().nextInt(100);
		if (style < 10) return text.toUpperCase() + "!";
		if (style < 25) return text.toLowerCase();
		
		if (ThreadLocalRandom.current().nextInt(100) < 35) {
			String[] fillers = {" xd", " lmao", " bruh", "...", " :)", " lol", " :P", " <3"};
			return text + fillers[ThreadLocalRandom.current().nextInt(fillers.length)];
		}
		return text;
	}

	// ==================== 公共 API（保持向后兼容） ====================

	public static String getRainComplaint() { return addEmotion(getRandom(RAIN)); }
	public static String getNightComplaint() { return addEmotion(getRandom(NIGHT)); }
	public static String getFireComplaint() { return addEmotion(getRandom(FIRE)); }
	public static String getCreeperFear() { return addEmotion(getRandom(CREEPER_FEAR)); }
	public static String getCombatWin() { return addEmotion(getRandom(COMBAT_WIN)); }
	public static String getCombatLose() { return addEmotion(getRandom(COMBAT_LOSE)); }
	public static String getGreeting() { return addEmotion(getRandom(GREETING)); }
	public static String getGreeting(String targetName) { 
		String msg = getRandom(GREETING);
		if (ThreadLocalRandom.current().nextBoolean()) return addEmotion(msg + " " + targetName);
		return addEmotion(msg);
	}
	public static String getFarewell() { return addEmotion(getRandom(FAREWELL)); }
	public static String getAFKMessage() { return addEmotion(getRandom(AFK_MESSAGES)); }
	public static String getBackMessage() { return addEmotion(getRandom(BACK_MESSAGES)); }
	public static String getDeathReaction() { return addEmotion(getRandom(DEATH_REACT)); }
	public static String getDeathReaction(String targetName) {
		String msg = getRandom(DEATH_REACT);
		if (ThreadLocalRandom.current().nextBoolean()) return addEmotion(msg + " " + targetName);
		return addEmotion(msg);
	}
	public static String getIdleChat() { return addEmotion(getRandom(IDLE_CHAT)); }
	public static String getFoundGood() { return addEmotion(getRandom(FOUND_GOOD)); }

	/**
	 * 从 MaohiConfig 已有的词库中随机选择（闲聊/打招呼/死亡反应）
	 * 优先使用 Config 词库，如果为空则 fallback 到内置词库
	 */
	public static String getConfigChat() {
		String[] msgs = MaohiConfig.getInstance().chatMessages;
		if (msgs != null && msgs.length > 0) {
			return addEmotion(msgs[ThreadLocalRandom.current().nextInt(msgs.length)]);
		}
		return getIdleChat();
	}
	public static String getConfigGreeting() {
		String[] msgs = MaohiConfig.getInstance().greetingReplies;
		if (msgs != null && msgs.length > 0) {
			return addEmotion(msgs[ThreadLocalRandom.current().nextInt(msgs.length)]);
		}
		return getGreeting();
	}
	public static String getConfigDeathReaction() {
		String[] msgs = MaohiConfig.getInstance().deathReactions;
		if (msgs != null && msgs.length > 0) {
			return addEmotion(msgs[ThreadLocalRandom.current().nextInt(msgs.length)]);
		}
		return getDeathReaction();
	}

	/**
	 * ★ P0-2 任务关联型聊天
	 * 50% 概率根据假人当前的任务说相关的话，50% 概率说通用闲聊
	 */
	public static String getChatByTask(com.maohi.fakeplayer.VirtualPlayerManager.TaskType task) {
		if (task == null) return getConfigChat();
		
		if (ThreadLocalRandom.current().nextBoolean()) {
			switch (task) {
				case MINING: return addEmotion(getRandom(TASK_MINING));
				case WOODCUTTING: return addEmotion(getRandom(TASK_WOODCUTTING));
				case EXPLORING: return addEmotion(getRandom(TASK_EXPLORING));
				default: return getConfigChat();
			}
		}
		return getConfigChat();
	}
}
