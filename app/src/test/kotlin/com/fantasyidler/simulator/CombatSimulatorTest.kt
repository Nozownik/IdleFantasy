package com.fantasyidler.simulator

import com.fantasyidler.data.json.BossCombatStats
import com.fantasyidler.data.json.BossCommonLoot
import com.fantasyidler.data.json.BossData
import com.fantasyidler.data.json.BossDefensiveStats
import com.fantasyidler.data.json.DungeonData
import com.fantasyidler.data.json.EnemyCombatStats
import com.fantasyidler.data.json.EnemyData
import com.fantasyidler.data.json.EnemyDefensiveStats
import com.fantasyidler.data.json.EnemySpawn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Tests for the most complex simulator, [CombatSimulator.simulateDungeon], made
 * deterministic through the injected `random` seam. Combat has too many moving
 * parts to assert exact per-tick damage, so this pins structural invariants
 * (frame count, non-negative HP, XP accrual) plus seeded reproducibility.
 */
class CombatSimulatorTest {

    private fun weakEnemy() = EnemyData(
        name = "rat",
        displayName = "Rat",
        hp = 1,
        combatStats = EnemyCombatStats(
            attackLevel = 1, strengthLevel = 1, defenseLevel = 1,
            attackBonus = 0, strengthBonus = 0,
        ),
        defensiveStats = EnemyDefensiveStats(
            attackDefense = 0, strengthDefense = 0, rangedDefense = 0, magicDefense = 0,
        ),
        // Combat XP per kill is read from the "combat" key (see CombatSimulator).
        xpDrops = mapOf("combat" to 20),
    )

    private fun dungeon(spawns: List<EnemySpawn>) = DungeonData(
        name = "farm",
        displayName = "Farm",
        description = "",
        recommendedLevel = 1,
        encounterRate = 1.0,
        enemySpawns = spawns,
    )

    /** A player strong enough to one-shot the weak enemy and never die. */
    private fun runStrongPlayer(seed: Int) = CombatSimulator.simulateDungeon(
        dungeon = dungeon(listOf(EnemySpawn("rat", 1))),
        enemies = mapOf("rat" to weakEnemy()),
        playerAttack = 99,
        playerStrength = 99,
        playerDefence = 99,
        playerHp = 99,
        weaponStrengthBonus = 64,
        random = Random(seed),
    )

    /** An unkillable wall so every melee swing targets a living enemy. */
    private fun tankyEnemy(defense: Int) = EnemyData(
        name = "golem",
        displayName = "Golem",
        hp = 1_000_000,
        combatStats = EnemyCombatStats(
            attackLevel = 1, strengthLevel = 1, defenseLevel = 1,
            attackBonus = 0, strengthBonus = 0,
        ),
        defensiveStats = EnemyDefensiveStats(
            attackDefense = defense, strengthDefense = defense, rangedDefense = defense, magicDefense = defense,
        ),
        xpDrops = mapOf("combat" to 20),
    )

    private fun totalMeleeDamage(seed: Int, defense: Int = 0, doubleHitChance: Double = 0.0, secondChance: Boolean = false) =
        CombatSimulator.simulateDungeon(
            dungeon = dungeon(listOf(EnemySpawn("golem", 1))),
            enemies = mapOf("golem" to tankyEnemy(defense)),
            playerAttack = 99,
            playerStrength = 99,
            playerDefence = 99,
            playerHp = 999,
            weaponStrengthBonus = 64,
            doubleHitChance = doubleHitChance,
            secondChance = secondChance,
            random = Random(seed),
        ).frames.sumOf { it.playerHits.sum() }

    @Test
    fun `a survivable dungeon run produces 60 frames`() {
        assertEquals(60, runStrongPlayer(seed = 1).frames.size)
    }

    @Test
    fun `guaranteed double hit roughly doubles melee damage`() {
        val base    = totalMeleeDamage(seed = 11)
        val doubled = totalMeleeDamage(seed = 11, doubleHitChance = 1.0)
        assertTrue("expected ~2x damage, got base=$base doubled=$doubled", doubled > base * 1.6)
    }

    @Test
    fun `second chance raises accuracy against a high-defense enemy`() {
        // Hit chance clamps to 0.15 here; one reroll lifts it to 1 - 0.85^2 = 0.2775.
        val base     = totalMeleeDamage(seed = 13, defense = 100_000)
        val rerolled = totalMeleeDamage(seed = 13, defense = 100_000, secondChance = true)
        assertTrue("expected ~1.85x damage, got base=$base rerolled=$rerolled", rerolled > base * 1.4)
    }

    @Test
    fun `the same seed reproduces an identical run`() {
        assertEquals(runStrongPlayer(seed = 42).frames, runStrongPlayer(seed = 42).frames)
    }

    @Test
    fun `a strong player accrues combat XP, scores kills, and never dies`() {
        val frames = runStrongPlayer(seed = 7).frames
        val totalXp = frames.sumOf { frame -> frame.xpBySkill.values.sum() }
        val totalKills = frames.sumOf { it.kills }

        assertTrue("expected positive combat XP", totalXp > 0)
        assertTrue("expected at least one kill", totalKills > 0)
        assertFalse("strong player should not die", frames.any { it.died })
    }

    @Test
    fun `recorded HP is never negative`() {
        assertTrue(runStrongPlayer(seed = 3).frames.all { it.hpAfter >= 0 })
    }

    @Test
    fun `the default attack speed still yields 25-tick frames`() {
        assertTrue(runStrongPlayer(seed = 5).frames.all { it.playerHits.size == 25 })
    }

    @Test
    fun `a fast ranged weapon consumes more than the legacy 1500-arrow cap`() {
        val result = CombatSimulator.simulateDungeon(
            dungeon = dungeon(listOf(EnemySpawn("rat", 1))),
            enemies = mapOf("rat" to weakEnemy()),
            playerAttack = 99,
            playerStrength = 99,
            playerDefence = 99,
            playerHp = 999,
            combatStyle = "ranged",
            playerRanged = 99,
            availableArrows = mapOf("bronze_arrow" to 10_000),
            attackSpeedSec = 1.5,
            random = Random(1),
        )
        assertEquals(60, result.frames.size)
        assertTrue(result.frames.all { it.playerHits.size == 40 })
        val totalArrows = result.frames.sumOf { it.arrowsConsumed.values.sum() }
        assertEquals(2_400, totalArrows)
    }

    @Test
    fun `a fast magic weapon consumes runes at the scaled tick rate`() {
        val result = CombatSimulator.simulateDungeon(
            dungeon = dungeon(listOf(EnemySpawn("rat", 1))),
            enemies = mapOf("rat" to weakEnemy()),
            playerAttack = 99,
            playerStrength = 99,
            playerDefence = 99,
            playerHp = 999,
            combatStyle = "magic",
            playerMagic = 99,
            spellMaxHit = 10,
            runeKey = "fire_rune",
            runeCostPerAttack = 2,
            availableRunes = 1_000_000,
            attackSpeedSec = 1.5,
            random = Random(2),
        )
        assertEquals(60, result.frames.size)
        val totalRunes = result.frames.sumOf { it.runesConsumed.values.sum() }
        assertEquals(60 * 40 * 2, totalRunes)
    }

    @Test
    fun `an empty spawn pool yields no frames but a valid duration`() {
        val result = CombatSimulator.simulateDungeon(
            dungeon = dungeon(emptyList()),
            enemies = emptyMap(),
            playerAttack = 10,
            playerStrength = 10,
            playerDefence = 10,
            agilityLevel = 1,
        )
        assertTrue(result.frames.isEmpty())
        assertEquals(SkillSimulator.sessionDurationMs(1), result.durationMs)
    }

    // ------------------------------------------------------------------
    // Raids: mercenary parties vs raid-tier bosses
    // ------------------------------------------------------------------

    /** The real raid bosses from the shipped data file, so tuning and tests cannot drift apart. */
    private val raidBosses: List<BossData> by lazy {
        val json = Json { ignoreUnknownKeys = true }
        val text = File("src/main/assets/data/raid_bosses.json").readText()
        json.decodeFromString<Map<String, BossData>>(text).values.filter { it.raid }
    }

    /** Stats mirroring the elite roster tier in mercenaries.json (99+100 atk, effStr 209 with +110 gear, hp 380). */
    private fun eliteMerc(i: Int) = CombatSimulator.MercCombatant(
        id = "elite_$i", style = "melee", effAttack = 199, maxHit = 57, defense = 95, hpLevel = 380,
    )

    /** BIS-league solo player: level 99s plus prestige stat nodes, strong gear bonuses, best food supply. */
    private fun runRaid(
        boss: BossData,
        seed: Int,
        mercs: List<CombatSimulator.MercCombatant>,
        maxedOut: Boolean = false,
    ) = CombatSimulator.simulateBoss(
        boss = boss, bossKey = boss.id,
        // maxedOut adds combat potions on top and enables the prestige combat specials.
        playerAttack = if (maxedOut) 132 else 122,
        playerStrength = if (maxedOut) 132 else 122,
        playerDefence = if (maxedOut) 132 else 122,
        playerHp = 99,
        weaponAttackBonus = 85, weaponStrBonus = 85,
        equippedFood = mapOf("cooked_shark" to 300),
        foodHealValues = mapOf("cooked_shark" to 20),
        attackSpeedSec = 1.5,
        doubleHitChance = if (maxedOut) 0.15 else 0.0,
        secondChance = maxedOut,
        mercenaries = mercs,
        random = Random(seed),
    )

    @Test
    fun `no raid boss can be beaten solo even fully maxed with prestige specials`() {
        assertTrue("expected raid bosses in data", raidBosses.isNotEmpty())
        for (boss in raidBosses) for (seed in listOf(1, 7, 42, 99, 1234)) {
            val frames = runRaid(boss, seed, mercs = emptyList(), maxedOut = true)
            assertEquals("${boss.id} seed $seed: maxed solo player must not win", 0, frames.last().kills)
        }
    }

    @Test
    fun `every raid boss falls to a player with three elite mercenaries`() {
        for (boss in raidBosses) for (seed in listOf(1, 7, 42, 99, 1234)) {
            val frames = runRaid(boss, seed, mercs = (1..3).map { eliteMerc(it) })
            assertEquals("${boss.id} seed $seed: full elite party must win", 1, frames.last().kills)
            val allyDamage = frames.sumOf { it.allyHits.sum() }
            assertTrue("${boss.id} seed $seed: mercs must contribute damage", allyDamage > 0)
        }
    }

    @Test
    fun `the boss spreads its attacks across the party`() {
        // Deterministic per seed: with 3 extra targets the player soaks far fewer hits.
        val boss = raidBosses.first()
        val soloTaken  = runRaid(boss, seed = 5, mercs = emptyList()).sumOf { it.enemyHits.sum() }
        val partyTaken = runRaid(boss, seed = 5, mercs = (1..3).map { eliteMerc(it) }).sumOf { it.enemyHits.sum() }
        assertTrue("expected party player to take less damage (solo=$soloTaken party=$partyTaken)", partyTaken < soloTaken)
    }

    @Test
    fun `spawns follow their weights across a full run`() {
        // Two equal-weight one-hit enemies: kills must land near 50/50 rather than one
        // type dominating whole frames or chaining across them (issue #1557).
        val result = CombatSimulator.simulateDungeon(
            dungeon = dungeon(listOf(EnemySpawn("rat", 1), EnemySpawn("bat", 1))),
            enemies = mapOf("rat" to weakEnemy(), "bat" to weakEnemy().copy(name = "bat", displayName = "Bat")),
            playerAttack = 99,
            playerStrength = 99,
            playerDefence = 99,
            playerHp = 99,
            weaponStrengthBonus = 64,
            random = Random(7),
        )
        val killsByEnemy = mutableMapOf<String, Int>()
        result.frames.forEach { f ->
            f.killsByEnemy.forEach { (k, v) -> killsByEnemy[k] = (killsByEnemy[k] ?: 0) + v }
        }
        val total = killsByEnemy.values.sum()
        assertTrue("expected a large kill sample, got $total", total > 200)
        val ratShare = (killsByEnemy["rat"] ?: 0).toDouble() / total
        assertTrue("expected both types near 50/50, got $killsByEnemy", ratShare in 0.40..0.60)
    }

    // Issue #1961: a boss timeout is decided from the health left, not full pools.
    @Test
    fun `timeout win uses remaining HP when the boss is nearly dead`() {
        // Boss ground to 10 of 1000 HP, player at 5 of 100, player deals 5x the boss's DPS:
        // 2s to finish the boss vs 5s to fall. Full pools (200s vs 100s) would call it a loss.
        assertTrue(CombatSimulator.timeoutWon(bossHpLeft = 10, partyHpLeft = 5, playerDps = 5.0, bossDps = 1.0))
    }

    @Test
    fun `timeout loss uses remaining HP when the party is nearly dead`() {
        // Boss almost untouched, party nearly dead: full pools (1000 vs 5000) would call this a win.
        assertFalse(CombatSimulator.timeoutWon(bossHpLeft = 990, partyHpLeft = 20, playerDps = 1.0, bossDps = 1.0))
    }

    @Test
    fun `timeout with zero dps falls back to comparing dps`() {
        assertTrue(CombatSimulator.timeoutWon(bossHpLeft = 500, partyHpLeft = 10, playerDps = 2.0, bossDps = 0.0))
        assertFalse(CombatSimulator.timeoutWon(bossHpLeft = 5, partyHpLeft = 500, playerDps = 0.0, bossDps = 2.0))
    }
    // ---------------------------------------------------------------------
    // Western stage 1: 4 independent styles + handgun cylinder/reload
    // ---------------------------------------------------------------------

    private fun handgunRun(seed: Int, cylinder: Int, reload: Int, strength: Int = 60, strBonus: Int = 30, defense: Int = 0) =
        CombatSimulator.simulateDungeon(
            dungeon = dungeon(listOf(EnemySpawn("golem", 1))),
            enemies = mapOf("golem" to tankyEnemy(defense)),
            playerAttack = 1,
            playerStrength = strength,
            playerDefence = 99,
            playerHp = 999,
            weaponStrengthBonus = strBonus,
            combatStyle = "strength",
            handgunCylinder = cylinder,
            handgunReloadTicks = reload,
            random = Random(seed),
        ).frames

    @Test
    fun `handgun reload ticks deal no damage and follow the cylinder`() {
        // 6 shots then 2 reload ticks: pattern of 8 ticks, ticks 6 and 7 must be 0.
        val hits = handgunRun(seed = 1, cylinder = 6, reload = 2).first().playerHits
        for (i in hits.indices) if (i % 8 >= 6) assertEquals("tick $i should be reloading", 0, hits[i])
    }

    @Test
    fun `a bigger cylinder deals more damage than a two-shot derringer`() {
        val six = handgunRun(seed = 3, cylinder = 6, reload = 2).sumOf { it.playerHits.sum() }
        val two = handgunRun(seed = 3, cylinder = 2, reload = 2).sumOf { it.playerHits.sum() }
        assertTrue("six=$six two=$two", six > two)
    }

    @Test
    fun `handgun damage uses the strength skill, not attack`() {
        val weak   = handgunRun(seed = 5, cylinder = 6, reload = 2, strength = 1).sumOf { it.playerHits.sum() }
        val strong = handgunRun(seed = 5, cylinder = 6, reload = 2, strength = 69).sumOf { it.playerHits.sum() }
        assertTrue("weak=$weak strong=$strong", strong > weak * 2)
    }

    @Test
    fun `fan the hammer unlocks at level 70`() {
        assertEquals(0.0, CombatSimulator.fanHammerChanceFor("strength", 69), 0.0)
        assertEquals(CombatSimulator.FAN_HAMMER_CHANCE, CombatSimulator.fanHammerChanceFor("strength", 70), 0.0)
        assertEquals(0.0, CombatSimulator.fanHammerChanceFor("attack", 99), 0.0)
        val fanned = handgunRun(seed = 9, cylinder = 6, reload = 2, strength = 70).flatMap { it.doubleHitTicks }
        assertTrue("expected fan-the-hammer double shots", fanned.isNotEmpty())
    }

    @Test
    fun `handgun max hit is about 70 percent of the equivalent long-gun shot`() {
        val longGun = 1 + (80 + 40) * (40 + 64) / 640
        val hg = CombatSimulator.handgunMaxHit(80, 40)
        assertTrue("hg=$hg long=$longGun", hg in (longGun * 0.6).toInt()..(longGun * 0.8).toInt() + 1)
    }

    @Test
    fun `melee max hit depends on attack only`() {
        val a = CombatSimulator.meleeMaxHit(99, 64)
        val b = CombatSimulator.meleeMaxHit(10, 64)
        assertTrue(a > b)
        // Strength level no longer matters for melee damage.
        val lowStr  = totalMeleeDamageWith(strength = 1)
        val highStr = totalMeleeDamageWith(strength = 99)
        assertEquals(lowStr, highStr)
    }

    private fun totalMeleeDamageWith(strength: Int) = CombatSimulator.simulateDungeon(
        dungeon = dungeon(listOf(EnemySpawn("golem", 1))),
        enemies = mapOf("golem" to tankyEnemy(0)),
        playerAttack = 99, playerStrength = strength, playerDefence = 99, playerHp = 999,
        weaponStrengthBonus = 64, combatStyle = "attack", random = Random(21),
    ).frames.sumOf { it.playerHits.sum() }

    @Test
    fun `handgun XP goes to strength and melee XP to attack`() {
        val hgXp = handgunRun(seed = 2, cylinder = 6, reload = 2).flatMap { it.xpBySkill.keys }.toSet()
        assertTrue(hgXp.isEmpty() || "strength" in hgXp)
        val melee = runStrongPlayer(seed = 2).frames.flatMap { it.xpBySkill.keys }.toSet()
        assertTrue("attack" in melee)
        assertFalse("strength" in melee)
    }

    // --- Revolver lineup data rules (brass < steel, Navy .36 < Army .44) ---

    private val equipment: Map<String, kotlinx.serialization.json.JsonObject> by lazy {
        val f = listOf("src/main/assets/data/equipment.json", "app/src/main/assets/data/equipment.json").map(::File).first { it.exists() }
        Json.parseToJsonElement(f.readText()).let { el ->
            (el as kotlinx.serialization.json.JsonObject).mapValues { it.value as kotlinx.serialization.json.JsonObject }
        }
    }
    private fun str(key: String) = equipment.getValue(key)["strength_bonus"].toString().toInt()
    private fun int(key: String, f: String) = equipment.getValue(key)[f].toString().toInt()

    @Test
    fun `brass-frame Remingtons are weaker than steel-frame ones`() {
        assertTrue(str("steel_battleaxe") < str("adamantite_battleaxe"))   // Navy brass < Navy steel
        assertTrue(str("mithril_warhammer") < str("runite_warhammer"))     // Army brass < Army steel
    }

    @Test
    fun `navy 36 revolvers are weaker than army 44 at the same tier`() {
        assertTrue(str("steel_battleaxe") < str("mithril_warhammer"))      // Remington Navy brass < Army brass
        assertTrue(str("adamantite_battleaxe") < str("runite_warhammer"))  // Remington Navy steel < Army steel
        assertTrue(str("mithril_battleaxe") < str("adamantite_warhammer")) // Colt 1851 Navy < 1860 Army
    }

    @Test
    fun `every strength-style weapon is a handgun with a cylinder`() {
        val hg = equipment.filterValues { it["slot"].toString() == "\"weapon\"" && it["combat_style"].toString() == "\"strength\"" }
        assertTrue(hg.size >= 15)
        for ((k, v) in hg) assertTrue("$k missing cylinder_size", v.containsKey("cylinder_size"))
        assertEquals(5, int("bronze_battleaxe", "cylinder_size")) // Colt Paterson
        assertEquals(6, int("iron_warhammer", "cylinder_size"))   // Colt Walker
        assertEquals(2, int("bronze_warhammer", "cylinder_size")) // Derringer
    }
}
