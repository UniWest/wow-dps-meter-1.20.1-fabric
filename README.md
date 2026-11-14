# DPS & Spell Meter (Minecraft 1.20.1 Fabric)

Real-time DPS, Damage, and Spell Meter mod written in **Kotlin** for **Minecraft 1.20.1 (Fabric)**. Built for the **Prominence II: Hasturian Era** RPG modpack while remaining 100% compatible with vanilla Minecraft + Fabric API.

## ✨ Key Features

- **Exact Spell & Magic Detection (`Spell Engine`)**: Detects the actual cast spell (name, elemental school, and spell icon) rather than only the staff or sword used to cast it.
- **Expandable Weapon-to-Spell Breakdown (`🪄 Развёртка / Breakdown`)**: Clickable tree view showing every staff, wand, sword, or bow and all spells/abilities released from it with hit counts, crit %, avg/max hit, and damage share.
- **4 Interactive Graph Modes**:
  1. Bar DPS Timeline (with crit highlights & average DPS curve + hover tooltips)
  2. Area Curve (Raw vs Critical damage)
  3. Donut Pie Charts (Spells & Targets breakdown)
  4. Hit Range Histogram & Target Comparison
- **Freely Resizable & Auto-Adapting HUD**: Drag the bottom-right corner `◢` (in the Inspector `[K]` or directly while Chat `[T]` is open) to resize both width and height; fonts, columns, spell mini-breakdown, and mini DPS graph adapt automatically.
- **Party & Teammate Support**: Optional soft-reflection integration with **FTB Teams**, **Open Parties and Claims (OPAC)**, and vanilla Scoreboard Teams (`All / Party / Me` filter).
- **Target Dummy & Modded Mobs**: Full support for *MmmMmmMmmMmm* (`dummmmmmy`) target dummies and live 3D/2D entity portrait rendering for modded mobs and bosses.
- **Strict Combat Trigger**: Activates combat mode only on damage involving mobs, bosses, or players—ignoring passive environmental damage (falling, drowning, suffocation, etc.).
- **Auto RU / EN Localization**: Automatically switches UI, graphs, tooltips, and chat reports between Russian and English based on Minecraft's client language setting.

## ⌨️ Default Controls

- **`K`** — Open Detailed Report & Graphs Inspector
- **`H`** — Hide / Show Small DPS HUD
- **`Alt + R`** — Reset Combat Statistics
- *(All keybindings are customizable in Minecraft Controls and dynamically update on the HUD)*

## 🛠️ Building from Source

Requires **Java 21** (to run Gradle 8.14 / Fabric Loom 1.11.1; targets Java 17 bytecode for Minecraft 1.20.1):

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat build
```

The compiled mod JAR will be output to `build/libs/wow-dps-meter-8.2.0.jar`.

## 📦 Dependencies

- **Required**: Minecraft `1.20.1`, Fabric Loader `>=0.15.0`, Fabric API, Fabric Language Kotlin `>=1.10.0`
- **Optional (Soft Integration)**: Spell Engine, FTB Teams, Open Parties and Claims, Target Dummy (`dummmmmmy`)
