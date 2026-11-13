package com.snowy.paydash;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Snowy's Client: a read-only dashboard. It only READS payment lines from your own chat
 * ("Name paid you $X" / "You paid Name $X") and shows them. It never sends chat,
 * commands or packets, and it never automates anything.
 *
 * Open it with /paydash   (also: /paydash reset   /paydash clear)
 */
public class PayDashClient implements ClientModInitializer {
    static final boolean USE_FONT = true;   // false = Minecraft's own font

    // ---------- data ----------
    record Pay(long ts, boolean in, double amount, String name) {}

    static final List<Pay> PAYS = new ArrayList<>();      // oldest first
    static long sessionStart = System.currentTimeMillis();
    static boolean openRequested = false;

    // ----- Streamers: on-screen text, win streak, payment popups, hiding small payments in chat -----
    static boolean ovOn = true, ovRight = true;
    static final float[] OV_SIZES = {0.8f, 1.0f, 1.25f, 1.5f};
    static int ovSizeIdx = 1;
    static final String[] OV_LINES = {"", "", "", ""};

    static boolean wsOn = true;
    static int wsStreak = 0, wsBest = 0;

    static boolean pupOn = true, pupSent = false;
    static final double[] PUP_MINS = {0, 1e6, 10e6, 100e6, 500e6, 1e9, 5e9};
    static final int[] PUP_SECS = {2, 3, 4, 5, 6, 8, 10};
    static int pupMinIdx = 0, pupSecIdx = 2;
    record Pop(String name, double amt, boolean in) {}
    static final ArrayDeque<Pop> POPS = new ArrayDeque<>();
    static long popHeadStart = 0;

    static boolean hideOn = false, hideRecv = true, hideSent = true;
    static final double[] HIDE_MINS = {1e6, 10e6, 50e6, 100e6, 500e6, 1e9};
    static int hideMinIdx = 2;

    // Win streak look
    static boolean wsAccentBar = true, wsBorder = false, wsSeparate = true, wsCmds = true;
    static int wsSizeIdx = 1, wsOpacity = 70;
    // Profit box (profit of the last 24 hours)
    static boolean phOn = false, phAccent = true, phBorder = true;
    static int phSizeIdx = 1, phOpacity = 75, phPos = 3;     // position: 0 top left, 1 top right, 2 bottom left, 3 bottom right
    static final String[] PH_POS = {"Top left", "Top right", "Bottom left", "Bottom right"};
    // Popup look
    static final String[] PUP_ANIMS = {"Slide down", "Fade", "Pop"};
    static int pupAnim = 0, pupOpacity = 95, pupBorder = 1, pupGlow = 50, pupSparkles = 8, pupSizeIdx = 1;
    static boolean pupTimerBar = true, pupPlus = true, pupFace = true, pupRainbow = false, pupShine = true, pupCountUp = true, pupBounce = true;
    // Chat colours and tax
    static boolean ccOn = false, ccBoldNames = false, ccBoldAmts = false, taxColor = true, taxShow = false, taxCount = false;
    static int taxPct = 20;                                   // tenths of a percent
    // Ask before a big /pay (it never sends anything: you press Enter yourself afterwards)
    static final double[] PC_MINS = {100e6, 500e6, 1e9, 5e9, 10e9, 50e9};
    static boolean pcOn = false, pcRainbow = false, pendingTest = false;
    static int pcMinIdx = 3, pcDelay = 5, pcDim = 55;         // delay in tenths of a second
    static String payApprovedCmd = "";
    static long payApprovedUntil = 0;
    static String[] pendingPay = null;                        // {name, amount text, full command}
    // Sounds
    static final String[] SND_NAMES = {"Arrow Ding", "Challenge Done", "Level Up", "Orb"};
    static boolean sndOn = false, sndQueued = true, cnfSndOn = true;
    static int sndIdx = 0, sndVol = 60, sndPitch = 10, sndRepeat = 1, cnfSndIdx = 3;
    static int sndRepeatLeft = 0;
    static long sndNext = 0;
    static boolean popFresh = false;
    // Colours: click a swatch to step through the palette
    static final int[] PAL = {0xFFFFC400, 0xFFFF8A00, 0xFFF14A7B, 0xFFFF4040, 0xFF7CFF3A, 0xFF59F58C, 0xFF40C8FF, 0xFFA27BFF, 0xFFFF8AC8, 0xFFFFFFFF,
            0xFF9EA0B4, 0xFF4DA3FF, 0xFF8EC5FF, 0xFF0B0B14, 0xFF0B1220};
    static final int K_GAIN = 0, K_LOSS = 1, K_ACCENT = 2, K_OVERLAY = 3, K_LABEL = 4, K_NUMBER = 5, K_NAME = 6, K_RECV = 7, K_SENT = 8, K_TAX = 9,
            K_TITLE = 10, K_ZERO = 11, K_PHBG = 12, K_PAMT = 13, K_PNAME = 14, K_PBG = 15, K_PBORDER = 16, K_TIMER = 17, K_CBG = 18, K_CNAME = 19,
            K_CAMT = 20, K_CACCENT = 21, K_CCONFIRM = 22, K_CCANCEL = 23;
    static final int[] CI = {5, 2, 7, 9, 10, 9, 1, 4, 3, 10, 12, 9, 14, 11, 12, 14, 7, 0, 14, 12, 3, 11, 11, 3};
    static int col(int k) { return PAL[CI[k]]; }

    static int hue(float h) {
        float r = Math.max(0, Math.min(1, Math.abs(h * 6 - 3) - 1));
        float gr = Math.max(0, Math.min(1, 2 - Math.abs(h * 6 - 2)));
        float b = Math.max(0, Math.min(1, 2 - Math.abs(h * 6 - 4)));
        return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (gr * 255) << 8) | (int) (b * 255);
    }

    static SoundEvent sndEvent(int i) {
        return switch (i) {
            case 1 -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
            case 2 -> SoundEvents.PLAYER_LEVELUP;
            case 3 -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            default -> SoundEvents.ARROW_HIT_PLAYER;
        };
    }

    static void playSnd(int i) {
        try {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sndEvent(i), sndPitch / 10f, sndVol / 100f));
        } catch (Exception ignored) { }
    }

    static void playOnce() { playSnd(sndIdx); }

    static void startSound() {
        playOnce();
        sndRepeatLeft = Math.max(0, sndRepeat - 1);
        sndNext = System.currentTimeMillis() + 250;
    }

    // The Streamers settings are rows: 0 switch, 1 stepper (- value +), 2 picker (< value >), 3 colour swatch, 4 wide button
    record Row(int type, String label, java.util.function.Supplier<String> val, BooleanSupplier on, java.util.function.IntSupplier color, Runnable minus, Runnable plus) {}

    static Row rSwitch(String label, BooleanSupplier on, Runnable toggle) { return new Row(0, label, null, on, null, null, toggle); }
    static Row rStep(String label, java.util.function.Supplier<String> val, Runnable minus, Runnable plus) { return new Row(1, label, val, null, null, minus, plus); }
    static Row rPick(java.util.function.Supplier<String> val, Runnable minus, Runnable plus) { return new Row(2, "", val, null, null, minus, plus); }
    static Row rSwatch(String label, java.util.function.IntSupplier color, Runnable cycle) { return new Row(3, label, null, null, color, null, cycle); }
    static Row rWide(String label, Runnable action) { return new Row(4, label, null, null, null, null, action); }

    static int clampI(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static final String[] SUB_NAMES = {"Chat colors", "Chat filter", "Profit HUD", "Win streak", "Popup alert", "Popup style", "Overlay", "Sounds", "Pay confirm"};
    static final String[] SUB_TITLES = {"How payment messages look in chat", "Hide small payments in chat", "The profit of the last 24 hours box", "Win streak counter (/win and /lose)",
            "The alert for big payments you receive", "Animations and looks of the popup alert", "Text lines on your screen", "Sound effects for alerts and confirmations",
            "The screen that asks before a big /pay"};
    static final int[] SUB_COLORS = {0xFF2F6B2F, 0xFF1A4F7A, 0xFF7A6A1A, 0xFF8A4B14, 0xFF8A2A6A, 0xFF6B3A66, 0xFF3B4EA8, 0xFF14706A, 0xFF8A2A2A};
    static final List<List<Row>> SUBS = buildSubs();

    static Row rSwatchK(String label, int k) {
        return rSwatch(label, () -> col(k), () -> { CI[k] = (CI[k] + 1) % PAL.length; save(); });
    }

    static Row rToggleB(String label, BooleanSupplier on, Runnable flip) { return rSwitch(label, on, () -> { flip.run(); save(); }); }

    static List<List<Row>> buildSubs() {
        List<List<Row>> all = new ArrayList<>();
        // 0 chat colors
        all.add(List.of(
            rToggleB("Chat colors", () -> ccOn, () -> ccOn = !ccOn),
            rToggleB("Show tax", () -> taxShow, () -> taxShow = !taxShow),
            rSwatchK("Name", K_NAME), rSwatchK("Received $", K_RECV), rSwatchK("Sent $", K_SENT), rSwatchK("Tax text", K_TAX),
            rToggleB("Color the tax", () -> taxColor, () -> taxColor = !taxColor),
            rToggleB("Bold names", () -> ccBoldNames, () -> ccBoldNames = !ccBoldNames),
            rToggleB("Bold amounts", () -> ccBoldAmts, () -> ccBoldAmts = !ccBoldAmts),
            rToggleB("Count tax in profit", () -> taxCount, () -> taxCount = !taxCount),
            rStep("Tax %", () -> String.format(Locale.US, "%.1f", taxPct / 10.0), () -> { taxPct = clampI(taxPct - 5, 0, 100); save(); }, () -> { taxPct = clampI(taxPct + 5, 0, 100); save(); })));
        // 1 chat filter
        all.add(List.of(
            rToggleB("Hide small", () -> hideOn, () -> hideOn = !hideOn),
            rPick(() -> "Hide under: " + money(HIDE_MINS[hideMinIdx]), () -> { hideMinIdx = clampI(hideMinIdx - 1, 0, HIDE_MINS.length - 1); save(); }, () -> { hideMinIdx = clampI(hideMinIdx + 1, 0, HIDE_MINS.length - 1); save(); }),
            rToggleB("Hide received", () -> hideRecv, () -> hideRecv = !hideRecv),
            rToggleB("Hide sent", () -> hideSent, () -> hideSent = !hideSent)));
        // 2 profit HUD
        all.add(List.of(
            rToggleB("Show HUD", () -> phOn, () -> phOn = !phOn),
            rToggleB("Accent bar", () -> phAccent, () -> phAccent = !phAccent),
            rToggleB("Border", () -> phBorder, () -> phBorder = !phBorder),
            rStep("Size", () -> OV_SIZES[phSizeIdx] + "x", () -> { phSizeIdx = clampI(phSizeIdx - 1, 0, OV_SIZES.length - 1); save(); }, () -> { phSizeIdx = clampI(phSizeIdx + 1, 0, OV_SIZES.length - 1); save(); }),
            rStep("Opacity", () -> phOpacity + "%", () -> { phOpacity = clampI(phOpacity - 5, 10, 100); save(); }, () -> { phOpacity = clampI(phOpacity + 5, 10, 100); save(); }),
            rSwatchK("Title", K_TITLE), rSwatchK("Gain", K_GAIN), rSwatchK("Loss", K_LOSS), rSwatchK("Zero", K_ZERO), rSwatchK("BG", K_PHBG), rSwatchK("Accent", K_ACCENT),
            rPick(() -> "Position: " + PH_POS[phPos], () -> { phPos = (phPos + PH_POS.length - 1) % PH_POS.length; save(); }, () -> { phPos = (phPos + 1) % PH_POS.length; save(); })));
        // 3 win streak
        all.add(List.of(
            rToggleB("Show streak", () -> wsOn, () -> wsOn = !wsOn),
            rToggleB("Separate HUD", () -> wsSeparate, () -> wsSeparate = !wsSeparate),
            rToggleB("/win and /lose", () -> wsCmds, () -> wsCmds = !wsCmds),
            rStep("Size", () -> OV_SIZES[wsSizeIdx] + "x", () -> { wsSizeIdx = clampI(wsSizeIdx - 1, 0, OV_SIZES.length - 1); save(); }, () -> { wsSizeIdx = clampI(wsSizeIdx + 1, 0, OV_SIZES.length - 1); save(); }),
            rToggleB("Accent bar", () -> wsAccentBar, () -> wsAccentBar = !wsAccentBar),
            rToggleB("Border", () -> wsBorder, () -> wsBorder = !wsBorder),
            rStep("Opacity", () -> wsOpacity + "%", () -> { wsOpacity = clampI(wsOpacity - 10, 10, 100); save(); }, () -> { wsOpacity = clampI(wsOpacity + 10, 10, 100); save(); }),
            rStep("Streak", () -> String.valueOf(wsStreak), () -> { wsStreak = Math.max(0, wsStreak - 1); save(); }, () -> wsWin()),
            rSwatchK("Label", K_LABEL), rSwatchK("Number", K_NUMBER),
            rWide("Reset streak", () -> { wsStreak = 0; save(); })));
        // 4 popup alert
        all.add(List.of(
            rToggleB("Show popup", () -> pupOn, () -> pupOn = !pupOn),
            rToggleB("Skin face", () -> pupFace, () -> pupFace = !pupFace),
            rPick(() -> "Min: " + (pupMinIdx == 0 ? "Any" : money(PUP_MINS[pupMinIdx])), () -> { pupMinIdx = clampI(pupMinIdx - 1, 0, PUP_MINS.length - 1); save(); }, () -> { pupMinIdx = clampI(pupMinIdx + 1, 0, PUP_MINS.length - 1); save(); }),
            rStep("Duration", () -> PUP_SECS[pupSecIdx] + ".0s", () -> { pupSecIdx = clampI(pupSecIdx - 1, 0, PUP_SECS.length - 1); save(); }, () -> { pupSecIdx = clampI(pupSecIdx + 1, 0, PUP_SECS.length - 1); save(); }),
            rStep("Size", () -> OV_SIZES[pupSizeIdx] + "x", () -> { pupSizeIdx = clampI(pupSizeIdx - 1, 0, OV_SIZES.length - 1); save(); }, () -> { pupSizeIdx = clampI(pupSizeIdx + 1, 0, OV_SIZES.length - 1); save(); }),
            rToggleB("Rainbow border", () -> pupRainbow, () -> pupRainbow = !pupRainbow),
            rToggleB("Sparkles", () -> pupSparkles > 0, () -> pupSparkles = pupSparkles > 0 ? 0 : 8),
            rToggleB("Glow", () -> pupGlow > 0, () -> pupGlow = pupGlow > 0 ? 0 : 50),
            rToggleB("Shine", () -> pupShine, () -> pupShine = !pupShine),
            rToggleB("Sent too", () -> pupSent, () -> pupSent = !pupSent),
            rSwatchK("Amount", K_PAMT), rSwatchK("Name", K_PNAME), rSwatchK("BG", K_PBG), rSwatchK("Border", K_PBORDER),
            rWide("Test popup", () -> addPopup("ExamplePlayer", 5e9, true))));
        // 5 popup style
        all.add(List.of(
            rPick(() -> "Animation: " + PUP_ANIMS[pupAnim], () -> { pupAnim = (pupAnim + PUP_ANIMS.length - 1) % PUP_ANIMS.length; save(); }, () -> { pupAnim = (pupAnim + 1) % PUP_ANIMS.length; save(); }),
            rStep("Opacity", () -> pupOpacity + "%", () -> { pupOpacity = clampI(pupOpacity - 5, 50, 100); save(); }, () -> { pupOpacity = clampI(pupOpacity + 5, 50, 100); save(); }),
            rStep("Border width", () -> pupBorder + "px", () -> { pupBorder = clampI(pupBorder - 1, 0, 3); save(); }, () -> { pupBorder = clampI(pupBorder + 1, 0, 3); save(); }),
            rStep("Glow", () -> pupGlow + "%", () -> { pupGlow = clampI(pupGlow - 25, 0, 100); save(); }, () -> { pupGlow = clampI(pupGlow + 25, 0, 100); save(); }),
            rStep("Sparkles", () -> String.valueOf(pupSparkles), () -> { pupSparkles = clampI(pupSparkles - 2, 0, 20); save(); }, () -> { pupSparkles = clampI(pupSparkles + 2, 0, 20); save(); }),
            rToggleB("+ before amount", () -> pupPlus, () -> pupPlus = !pupPlus),
            rToggleB("Count-up amount", () -> pupCountUp, () -> pupCountUp = !pupCountUp),
            rToggleB("Face bounce", () -> pupBounce, () -> pupBounce = !pupBounce),
            rToggleB("Timer bar", () -> pupTimerBar, () -> pupTimerBar = !pupTimerBar),
            rSwatchK("Timer color", K_TIMER),
            rWide("Test popup", () -> addPopup("ExamplePlayer", 5e9, true))));
        // 6 overlay
        Runnable flipSide = () -> { ovRight = !ovRight; save(); };
        all.add(List.of(
            rToggleB("Show", () -> ovOn, () -> ovOn = !ovOn),
            rStep("Size", () -> OV_SIZES[ovSizeIdx] + "x", () -> { ovSizeIdx = clampI(ovSizeIdx - 1, 0, OV_SIZES.length - 1); save(); }, () -> { ovSizeIdx = clampI(ovSizeIdx + 1, 0, OV_SIZES.length - 1); save(); }),
            rPick(() -> "Side: " + (ovRight ? "Right" : "Left"), flipSide, flipSide),
            rSwatchK("Color", K_OVERLAY)));
        // 7 sounds
        all.add(List.of(
            rToggleB("Popup sound", () -> sndOn, () -> sndOn = !sndOn),
            rPick(() -> "Sound: " + SND_NAMES[sndIdx], () -> { sndIdx = (sndIdx + SND_NAMES.length - 1) % SND_NAMES.length; save(); }, () -> { sndIdx = (sndIdx + 1) % SND_NAMES.length; save(); }),
            rStep("Volume", () -> sndVol + "%", () -> { sndVol = clampI(sndVol - 10, 10, 100); save(); }, () -> { sndVol = clampI(sndVol + 10, 10, 100); save(); }),
            rStep("Pitch", () -> String.format(Locale.US, "%.1f", sndPitch / 10.0), () -> { sndPitch = clampI(sndPitch - 1, 5, 20); save(); }, () -> { sndPitch = clampI(sndPitch + 1, 5, 20); save(); }),
            rStep("Repeat", () -> sndRepeat + "x", () -> { sndRepeat = clampI(sndRepeat - 1, 1, 5); save(); }, () -> { sndRepeat = clampI(sndRepeat + 1, 1, 5); save(); }),
            rToggleB("Also when queued", () -> sndQueued, () -> sndQueued = !sndQueued),
            rWide("Preview popup sound", () -> startSound()),
            rToggleB("Confirm sound", () -> cnfSndOn, () -> cnfSndOn = !cnfSndOn),
            rPick(() -> "Confirm: " + SND_NAMES[cnfSndIdx], () -> { cnfSndIdx = (cnfSndIdx + SND_NAMES.length - 1) % SND_NAMES.length; save(); }, () -> { cnfSndIdx = (cnfSndIdx + 1) % SND_NAMES.length; save(); }),
            rWide("Preview confirm sound", () -> playSnd(cnfSndIdx))));
        // 8 pay confirm
        all.add(List.of(
            rToggleB("Ask before /pay", () -> pcOn, () -> pcOn = !pcOn),
            rPick(() -> "Ask from: " + money(PC_MINS[pcMinIdx]), () -> { pcMinIdx = clampI(pcMinIdx - 1, 0, PC_MINS.length - 1); save(); }, () -> { pcMinIdx = clampI(pcMinIdx + 1, 0, PC_MINS.length - 1); save(); }),
            rStep("Delay", () -> String.format(Locale.US, "%.1fs", pcDelay / 10.0), () -> { pcDelay = clampI(pcDelay - 1, 0, 30); save(); }, () -> { pcDelay = clampI(pcDelay + 1, 0, 30); save(); }),
            rStep("Tax %", () -> String.format(Locale.US, "%.1f", taxPct / 10.0), () -> { taxPct = clampI(taxPct - 5, 0, 100); save(); }, () -> { taxPct = clampI(taxPct + 5, 0, 100); save(); }),
            rToggleB("Show tax", () -> taxShow, () -> taxShow = !taxShow),
            rToggleB("Rainbow border", () -> pcRainbow, () -> pcRainbow = !pcRainbow),
            rStep("Dim screen", () -> pcDim + "%", () -> { pcDim = clampI(pcDim - 5, 0, 90); save(); }, () -> { pcDim = clampI(pcDim + 5, 0, 90); save(); }),
            rSwatchK("Accent", K_CACCENT), rSwatchK("BG", K_CBG), rSwatchK("Name", K_CNAME), rSwatchK("Amount", K_CAMT),
            rSwatchK("Confirm btn", K_CCONFIRM), rSwatchK("Cancel btn", K_CCANCEL),
            rWide("Test confirm screen", () -> { pendingPay = new String[]{"ExamplePlayer", "5B", "pay ExamplePlayer 5B"}; pendingTest = true; })));
        return all;
    }

    // Places the rows of a sub-tab in two columns (a wide button takes a whole row). Returns {x, y, w, h} per row.
    static int[][] layout(List<Row> rows, int x, int y, int w) {
        int colW = (w - 6) / 2, col = 0, rowY = y;
        int[][] out = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).type() == 4) {
                if (col == 1) { col = 0; rowY += 19; }
                out[i] = new int[]{x, rowY, w, 16};
                rowY += 19;
            } else {
                out[i] = new int[]{x + col * (colW + 6), rowY, colW, 16};
                if (col == 1) { col = 0; rowY += 19; } else col = 1;
            }
        }
        return out;
    }

    static void wsWin() { wsStreak++; wsBest = Math.max(wsBest, wsStreak); save(); }

    static void wsLose() { wsStreak = 0; save(); }

    static void addPopup(String name, double amt, boolean in) {
        boolean first = POPS.isEmpty();
        if (POPS.size() < 5) {
            POPS.add(new Pop(name, amt, in));
            if (first) {
                popFresh = true;
                if (sndOn) startSound();
            }
        }
    }

    // Decides only whether a chat line is hidden. Hidden payments are still counted (see GAME_CANCELED below).
    static boolean shouldHide(String text) {
        if (!hideOn) return false;
        Pay p = parsePay(text);
        if (p == null || p.amount() >= HIDE_MINS[hideMinIdx]) return false;
        return p.in() ? hideRecv : hideSent;
    }

    static int alpha(int c, double k) {
        int a = (int) (((c >>> 24) & 255) * Math.max(0, Math.min(1, k)));
        return (a << 24) | (c & 0xFFFFFF);
    }

    // On-screen text lines, the profit box, the win streak box and the payment popup (drawn on the game screen)
    static void renderStream(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        Font f = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();

        if (ovOn) {
            float sc = OV_SIZES[ovSizeIdx];
            int yy = 10;
            for (String line : OV_LINES) {
                if (line.isEmpty()) continue;
                int wpx = (int) (twb(f, line) * sc);
                int xx = ovRight ? sw - 8 - wpx : 8;
                g.pose().pushMatrix();
                g.pose().translate(xx, yy);
                g.pose().scale(sc, sc);
                txb(g, f, line, 1, 1, 0xAA000000);
                txb(g, f, line, 0, 0, col(K_OVERLAY));
                g.pose().popMatrix();
                yy += (int) (11 * sc) + 1;
            }
        }

        if (sndRepeatLeft > 0 && now >= sndNext) {
            playOnce();
            sndRepeatLeft--;
            sndNext = now + 250;
        }

        boolean streakInBox = wsOn && !wsSeparate;
        if (phOn) {
            double net = 0;
            long since = now - 24L * 3600_000L;
            for (Pay q : PAYS) if (q.ts() >= since) net += q.in() ? q.amount() : -q.amount();
            float sc = OV_SIZES[phSizeIdx];
            int bw = (int) (88 * sc), bh = (int) ((streakInBox ? 42 : 30) * sc);
            int bx = (phPos == 1 || phPos == 3) ? sw - 8 - bw : 8;
            int by = phPos >= 2 ? sh - 8 - bh : 8;
            rrect(g, bx, by, bw, bh, alpha(col(K_PHBG), phOpacity / 100.0));
            if (phBorder) frame(g, bx, by, bw, bh, col(K_ACCENT));
            if (phAccent) g.fill(bx, by + 2, bx + 2, by + bh - 2, col(K_ACCENT));
            g.pose().pushMatrix();
            g.pose().translate(bx + 8 * sc, by + 5 * sc);
            g.pose().scale(sc, sc);
            small(g, f, "PROFIT 24H", 0, 0, col(K_TITLE), true);
            g.pose().popMatrix();
            g.pose().pushMatrix();
            g.pose().translate(bx + 8 * sc, by + 13 * sc);
            g.pose().scale(1.2f * sc, 1.2f * sc);
            txb(g, f, net == 0 ? "$0" : signed(net), 0, 0, net > 0 ? col(K_GAIN) : (net < 0 ? col(K_LOSS) : col(K_ZERO)));
            g.pose().popMatrix();
            if (streakInBox) {
                g.pose().pushMatrix();
                g.pose().translate(bx + 8 * sc, by + 29 * sc);
                g.pose().scale(sc, sc);
                small(g, f, "WIN STREAK " + wsStreak, 0, 0, col(K_LABEL), true);
                g.pose().popMatrix();
            }
        }

        if (wsOn && wsSeparate) {
            float sc = OV_SIZES[wsSizeIdx];
            int bw = (int) (62 * sc), bh = (int) (30 * sc), bx = 8, by = 60;
            rrect(g, bx, by, bw, bh, alpha(0xFF0B0B14, wsOpacity / 100.0));
            if (wsBorder) frame(g, bx, by, bw, bh, col(K_ACCENT));
            if (wsAccentBar) g.fill(bx, by + 2, bx + 2, by + bh - 2, col(K_ACCENT));
            g.pose().pushMatrix();
            g.pose().translate(bx + 8 * sc, by + 5 * sc);
            g.pose().scale(sc, sc);
            small(g, f, "WIN STREAK", 0, 0, col(K_LABEL), true);
            g.pose().popMatrix();
            g.pose().pushMatrix();
            g.pose().translate(bx + 8 * sc, by + 13 * sc);
            g.pose().scale(1.5f * sc, 1.5f * sc);
            txb(g, f, String.valueOf(wsStreak), 0, 0, col(K_NUMBER));
            g.pose().popMatrix();
        }

        if (!pupOn) { POPS.clear(); popHeadStart = 0; }
        Pop p = POPS.peek();
        if (p != null) {
            if (popHeadStart == 0) {
                popHeadStart = now;
                if (popFresh) popFresh = false; else if (sndOn && sndQueued) startSound();
            }
            long dur = PUP_SECS[pupSecIdx] * 1000L, el = now - popHeadStart;
            if (el >= dur) {
                POPS.poll();
                popHeadStart = 0;
            } else {
                double inK = Math.min(1.0, el / 250.0), outK = Math.min(1.0, (dur - el) / 300.0);
                double ease = 1 - Math.pow(1 - inK, 3);
                int popW = 170, popH = 26;
                float kSize = OV_SIZES[pupSizeIdx];
                int accent = p.in() ? col(K_PBORDER) : col(K_LOSS);
                int amtCol = p.in() ? col(K_PAMT) : col(K_LOSS);
                double a = outK * pupOpacity / 100.0;
                float topY = 8, animScale = 1f;
                if (pupAnim == 0) topY = (float) (-popH * kSize + (8 + popH * kSize) * ease);
                else if (pupAnim == 1) a *= inK;
                else animScale = (float) (0.6 + 0.4 * ease);
                float sc = kSize * animScale;
                g.pose().pushMatrix();
                g.pose().translate(sw / 2f, topY + popH * kSize / 2f);
                g.pose().scale(sc, sc);
                g.pose().translate(-popW / 2f, -popH / 2f);
                int x = 0, y = 0;
                if (pupGlow > 0) for (int k = 4; k >= 1; k--) rrect(g, x - k, y - k, popW + 2 * k, popH + 2 * k, alpha(accent, a * pupGlow / 100.0 * 0.12));
                rrect(g, x, y, popW, popH, alpha(col(K_PBG), a * 0.95));
                int bcol = pupRainbow ? hue((now % 3000) / 3000f) : col(K_PBORDER);
                if (pupBorder > 0) frameN(g, x, y, popW, popH, pupBorder, alpha(bcol, a));
                g.fill(x, y + 2, x + 2, y + popH - 2, alpha(accent, a));
                int tx0 = x + 8;
                if (pupFace) {
                    var conn = mc.getConnection();
                    var info = conn == null ? null : conn.getPlayerInfo(p.name());
                    if (info != null) {
                        double bk = pupBounce ? Math.max(0, 1 - el / 600.0) : 0;
                        int fy = y + 3 - (int) Math.round(Math.abs(Math.sin(el / 90.0)) * 4 * bk);
                        PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x + 5, fy, 20);
                        tx0 = x + 30;
                    }
                }
                small(g, f, p.in() ? "PAYMENT RECEIVED" : "PAYMENT SENT", tx0, y + 4, alpha(0xFFB8B8C8, a), true);
                double shown = pupCountUp ? p.amt() * Math.min(1.0, el / 700.0) : p.amt();
                String amtStr = (p.in() ? (pupPlus ? "+" : "") : "-") + money2(shown);
                txb(g, f, fit(f, p.name(), popW - (tx0 - x) - 14 - twb(f, amtStr)), tx0, y + 13, alpha(col(K_PNAME), a));
                txb(g, f, amtStr, x + popW - 8 - twb(f, amtStr), y + 13, alpha(amtCol, a));
                if (pupShine) {
                    int sx0 = x - 20 + (int) (((el % 1400) / 1400.0) * (popW + 40));
                    int l = Math.max(x, sx0), r = Math.min(x + popW, sx0 + 12);
                    if (r > l) g.fill(l, y + 1, r, y + popH - 1, alpha(0xFFFFFFFF, a * 0.12));
                }
                if (pupSparkles > 0) {
                    java.util.Random rr = new java.util.Random(7);
                    for (int i = 0; i < pupSparkles; i++) {
                        int sx2 = x - 6 + rr.nextInt(popW + 12), sy2 = y - 6 + rr.nextInt(popH + 12);
                        double tw2 = Math.abs(Math.sin(el / 220.0 + i * 1.7));
                        g.fill(sx2, sy2, sx2 + 2, sy2 + 2, alpha(0xFFFFE680, a * tw2));
                    }
                }
                if (pupTimerBar) g.fill(x + 4, y + popH - 3, x + 4 + (int) ((popW - 8) * (1.0 - (double) el / dur)), y + popH - 2, alpha(col(K_TIMER), a));
                g.pose().popMatrix();
            }
        }
    }

    static void frameN(GuiGraphicsExtractor g, int x, int y, int w, int h, int n, int c) {
        g.fill(x, y, x + w, y + n, c);
        g.fill(x, y + h - n, x + w, y + h, c);
        g.fill(x, y, x + n, y + h, c);
        g.fill(x + w - n, y, x + w, y + h, c);
    }

    // Backdrop themes: a gradient plus a scene. Only the picture behind the panels changes.
    // Scenes: 0 pine trees, 1 winter (sleigh), 2 aurora, 3 city, 4 sunset, 5 stars only, 6 dunes,
    //         7 Christmas tree, 8 cabin, 9 candy canes, 10 snowman
    static final String[] THEME_NAMES = {"Winter", "Aurora", "Galaxy", "Desert", "Aesthetic", "Volcano", "Space", "Red", "Orange", "Yellow", "Green",
            "Blue", "Purple", "Pink", "Teal", "Gold", "Black", "White", "Gray", "Mint", "Sunset", "Forest", "City",
            "Christmas Tree", "Cozy Cabin", "Candy Cane", "Snowman"};
    static final int[][] THEME_COL = {
        {0xFF0A1230, 0xFF2B4378, 1}, {0xFF040A1C, 0xFF0E2B38, 2}, {0xFF12062E, 0xFF3A1A78, 5}, {0xFF3A2312, 0xFFD78A3A, 6},
        {0xFF3A1A4E, 0xFFE58AC0, 0}, {0xFF1A0505, 0xFF8A1A10, 6}, {0xFF02040E, 0xFF0A1740, 5}, {0xFF2A0508, 0xFFB01526, 0},
        {0xFF2A1004, 0xFFD2601A, 0}, {0xFF2A2404, 0xFFC8A81A, 0}, {0xFF04200E, 0xFF1A8A3C, 0}, {0xFF04102E, 0xFF1F4FC8, 0},
        {0xFF16082E, 0xFF6A1FC8, 0}, {0xFF2A0820, 0xFFD8408A, 0}, {0xFF03202A, 0xFF1AA0B0, 0}, {0xFF2A1E04, 0xFFD8A21A, 0},
        {0xFF030304, 0xFF14141C, 0}, {0xFF8A90A8, 0xFFF2F4FA, 0}, {0xFF14161C, 0xFF5A6070, 0}, {0xFF04231C, 0xFF3AC8A0, 0},
        {0xFF24104A, 0xFFE2606E, 4}, {0xFF160B35, 0xFF3B2080, 0}, {0xFF0B0A28, 0xFF51296E, 3},
        {0xFF07122C, 0xFF1A4A3E, 7}, {0xFF0B1030, 0xFF2A3A6A, 8}, {0xFF3A0A12, 0xFFC42A3A, 9}, {0xFF0A1636, 0xFF2F5A92, 10}
    };
    static int theme = 21;   // Forest

    static int shade(int c, double k) {
        return 0xFF000000 | ((int) (((c >> 16) & 255) * k) << 16) | ((int) (((c >> 8) & 255) * k) << 8) | (int) ((c & 255) * k);
    }

    // Auctions: payments you receive while the timer runs are the bids. Highest single payment wins; a tie goes to the first payer.
    record Auc(long ts, String item, int amount, String winner, double bid, int bidders, int timeIdx) {}

    static final List<Auc> AUCS = new ArrayList<>();          // finished auctions, newest first
    static String aucItem = "Item";
    static int aucAmount = 1;
    static final int[] AUC_TIMES = {30, 60, 120, 180, 300, 600};
    static final double[] AUC_MINS = {0, 1e6, 5e6, 10e6, 25e6, 50e6, 100e6, 250e6, 500e6, 1e9};
    static int aucTimeIdx = 2, aucMinIdx = 2;
    static boolean aucActive = false, aucEnded = false, aucCutOff = true;
    static long aucEnd = 0;
    static String aucTop = null;
    static double aucTopBid = 0;
    static final java.util.Set<String> aucBidders = new java.util.HashSet<>();

    static void startAuction() {
        if (aucActive) return;
        aucActive = true;
        aucEnded = false;
        aucEnd = System.currentTimeMillis() + AUC_TIMES[aucTimeIdx] * 1000L;
        aucTop = null;
        aucTopBid = 0;
        aucBidders.clear();
    }

    static void finishAuction() {
        if (!aucActive) return;
        aucActive = false;
        aucEnded = true;
        AUCS.add(0, new Auc(System.currentTimeMillis(), aucItem, aucAmount, aucTop == null ? "-" : aucTop, aucTopBid, aucBidders.size(), aucTimeIdx));
        while (AUCS.size() > 20) AUCS.remove(AUCS.size() - 1);
        save();
    }

    static void ring(GuiGraphicsExtractor g, int cx, int cy, int r, double frac) {
        for (int deg = 0; deg < 360; deg += 2) {
            double ang = Math.toRadians(deg);
            int rx = cx + (int) Math.round(r * Math.sin(ang)), ry = cy - (int) Math.round(r * Math.cos(ang));
            g.fill(rx - 1, ry - 1, rx + 2, ry + 2, deg / 360.0 <= frac ? 0xFFF14A7B : 0x33FFFFFF);
        }
    }

    static String clock(long seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    // Settings. They are saved to config/snowys-hud.txt, which the balance HUD mod reads.
    static boolean hudBalance = true, hudProfit = true, hudRake = true;
    // Rakeback numbers come from the balance HUD mod, which writes them to config/snowys-rake.txt.
    static final Path RAKE_IN = FabricLoader.getInstance().getConfigDir().resolve("snowys-rake.txt");
    static double hudRate = 10, hudMin = 1e9;                       // percent, dollars
    static final List<Map.Entry<String, Double>> HUD_ROWS = new ArrayList<>();   // name, loss
    static boolean hudFound = false;
    static long hudStamp = 0, hudCheck = 0, hudAt = 0;

    static void readHud() {
        long now = System.currentTimeMillis();
        if (now - hudCheck < 500) return;
        hudCheck = now;
        try {
            long m = Files.getLastModifiedTime(RAKE_IN).toMillis();
            hudFound = true;
            hudAt = m;
            if (m == hudStamp) return;
            hudStamp = m;
            List<Map.Entry<String, Double>> rows = new ArrayList<>();
            double rate = hudRate, min = hudMin;
            for (String line : Files.readAllLines(RAKE_IN)) {
                try {
                    if (line.startsWith("rate=")) rate = Double.parseDouble(line.substring(5).trim());
                    else if (line.startsWith("min=")) min = Double.parseDouble(line.substring(4).trim());
                    else if (line.startsWith("p,")) {
                        String[] q = line.split(",", 3);
                        rows.add(Map.entry(q[1], Double.parseDouble(q[2])));
                    }
                } catch (Exception ignored) { }
            }
            hudRate = rate;
            hudMin = min;
            HUD_ROWS.clear();
            HUD_ROWS.addAll(rows);
        } catch (Exception e) {
            hudFound = false;
        }
    }
    static final Path CTL = FabricLoader.getInstance().getConfigDir().resolve("snowys-hud.txt");

    static boolean hudState(int i) { return i == 0 ? hudBalance : (i == 1 ? hudProfit : hudRake); }

    static void setHud(int i, boolean v) {
        if (i == 0) hudBalance = v; else if (i == 1) hudProfit = v; else hudRake = v;
    }

    static void saveCtl() {
        String t = "balance=" + (hudBalance ? 1 : 0) + "\nprofit=" + (hudProfit ? 1 : 0) + "\nrake=" + (hudRake ? 1 : 0)
                + "\n";
        try { Files.writeString(CTL, t); } catch (Exception ignored) { }
    }

    static void loadCtl() {
        try {
            for (String line : Files.readAllLines(CTL)) {
                try {
                    String[] kv = line.split("=", 2);
                    if (kv.length < 2) continue;
                    String k = kv[0].trim(), v = kv[1].trim();
                    switch (k) {
                        case "balance" -> hudBalance = v.equals("1");
                        case "profit" -> hudProfit = v.equals("1");
                        case "rake" -> hudRake = v.equals("1");
                        default -> { }
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    // ---------- payment lines in chat ----------
    static final String NAME_RE = "(?<name>[.\\w]{1,17})";
    static final String AMT_RE = "\\$[\\s\\u00A0]*(?<num>[\\d,]+(?:\\.\\d+)?)\\s*(?<suf>[kKmMbBtT])?";
    static final String PRE = "^(?:\\[[^\\]]*\\]\\s*|[^\\p{L}\\p{N}.])*";

    static Pattern rx(String s) {
        return Pattern.compile(s.replace(" ", "[\\s\\u00A0]+"));
    }

    static final Pattern[] IN = {
        rx(PRE + NAME_RE + " paid you " + AMT_RE),
        rx(PRE + NAME_RE + " sent you " + AMT_RE),
        rx(PRE + "You received " + AMT_RE + " from " + NAME_RE)
    };
    static final Pattern[] OUT = {
        rx(PRE + "You paid " + NAME_RE + " " + AMT_RE),
        rx(PRE + "You sent " + AMT_RE + " to " + NAME_RE)
    };

    // ---------- colours ----------
    static final int BG = 0xF2100A20, SIDE = 0xFF0A0616, CARD = 0xFF150F2B, EDGE = 0xFF2D2450;
    static final int PURPLE = 0xFF7A3CF0, PURPLE_EDGE = 0xFFA27BFF;
    static final int TITLE = 0xFFD07DAA, WHITE = 0xFFFBFEFF, MUTED = 0xFF9EA0B4;
    static final int GREEN = 0xFF59F58C, RED = 0xFFF14A7B, PINK = 0xFFF14A7B;

    // ---------- font ----------
    static final FontDescription FD = new FontDescription.Resource(Identifier.fromNamespaceAndPath("paydash", "hud"));

    static Component fnt(String s) {
        return USE_FONT ? Component.literal(s).withStyle(Style.EMPTY.withFont(FD)) : Component.literal(s);
    }

    static Component fntB(String s) {
        return USE_FONT ? Component.literal(s).withStyle(Style.EMPTY.withFont(FD).withBold(true)) : Component.literal(s);
    }

    static int tw(Font f, String s) { return f.width(fnt(s)); }

    static int twb(Font f, String s) { return f.width(fntB(s)); }

    static void tx(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        g.text(f, fnt(s), x, y, color, false);
    }

    static void txb(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        g.text(f, fntB(s), x, y, color, false);
    }

    static void small(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color, boolean bold) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.7f, 0.7f);
        if (bold) txb(g, f, s, 0, 0, color); else tx(g, f, s, 0, 0, color);
        g.pose().popMatrix();
    }

    static int spaced(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        int cx = x;
        for (char c : s.toCharArray()) {
            String ch = String.valueOf(c);
            txb(g, f, ch, cx, y, color);
            cx += f.width(fntB(ch)) + 1;
        }
        return cx - x;
    }

    static String fit(Font f, String s, int maxW) {
        if (tw(f, s) <= maxW) return s;
        while (s.length() > 1 && tw(f, s + "..") > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    // ---------- formatting ----------
    static String trim(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    static String money2(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) return String.format(Locale.US, "$%.2fT", a / 1e12);
        if (a >= 1e9) return String.format(Locale.US, "$%.2fB", a / 1e9);
        if (a >= 1e6) return String.format(Locale.US, "$%.2fM", a / 1e6);
        if (a >= 1e3) return String.format(Locale.US, "$%.2fK", a / 1e3);
        return "$" + trim(a);
    }

    static String money(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) return "$" + trim(a / 1e12) + "T";
        if (a >= 1e9) return "$" + trim(a / 1e9) + "B";
        if (a >= 1e6) return "$" + trim(a / 1e6) + "M";
        if (a >= 1e3) return "$" + trim(a / 1e3) + "K";
        return "$" + trim(a);
    }

    static String signed(double v) {
        return (v >= 0 ? "+" : "-") + money2(v);
    }

    static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 10) return "just now";
        if (s < 60) return s + "s ago";
        long m = s / 60;
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        return (h / 24) + "d ago";
    }

    static Double amount(String s) {
        if (s == null) return null;
        try {
            s = s.replace(",", "").replace("$", "").trim();
            double mult = 1;
            switch (Character.toLowerCase(s.charAt(s.length() - 1))) {
                case 'k' -> mult = 1e3;
                case 'm' -> mult = 1e6;
                case 'b' -> mult = 1e9;
                case 't' -> mult = 1e12;
                default -> { }
            }
            if (mult != 1) s = s.substring(0, s.length() - 1);
            return Double.parseDouble(s) * mult;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- start-up ----------
    @Override
    public void onInitializeClient() {
        load();
        loadCtl();

        // Commands typed by you (never sent to the server)
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            String[] a = cmd.trim().split("\\s+");
            if (wsCmds && a[0].equalsIgnoreCase("win")) { wsWin(); return false; }
            if (wsCmds && a[0].equalsIgnoreCase("lose")) { wsLose(); return false; }
            if (pcOn && a[0].equalsIgnoreCase("pay") && a.length >= 3) {
                Double pv = amount(a[2]);
                if (pv != null && pv >= PC_MINS[pcMinIdx]) {
                    String full = cmd.trim();
                    if (full.equals(payApprovedCmd) && System.currentTimeMillis() < payApprovedUntil) {
                        payApprovedCmd = "";
                        return true;   // you approved this exact command: let it through
                    }
                    pendingPay = new String[]{a[1], a[2], full};
                    return false;      // held back: a confirm screen opens, nothing is sent
                }
            }
            if (!a[0].equalsIgnoreCase("paydash")) return true;
            if (a.length < 2) {
                openRequested = true;
            } else if (a[1].equalsIgnoreCase("reset")) {
                sessionStart = System.currentTimeMillis();
                save();
            } else if (a[1].equalsIgnoreCase("item") && a.length > 2) {
                aucItem = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length));
                save();
            } else if (a[1].equalsIgnoreCase("win")) {
                wsWin();
            } else if (a[1].equalsIgnoreCase("lose")) {
                wsLose();
            } else if (a[1].equalsIgnoreCase("line") && a.length > 2) {
                try {
                    int n = Integer.parseInt(a[2]);
                    if (n >= 1 && n <= OV_LINES.length) {
                        OV_LINES[n - 1] = String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length));
                        save();
                    }
                } catch (Exception ignored) { }
            } else if (a[1].equalsIgnoreCase("clear")) {
                PAYS.clear();
                sessionStart = System.currentTimeMillis();
                save();
            }
            return false;
        });

        // Hide small payments in chat (only if you turn it on). They are still counted below.
        ClientReceiveMessageEvents.MODIFY_GAME.register((msg, overlay) -> overlay ? msg : restyle(msg));
        ClientReceiveMessageEvents.ALLOW_GAME.register((msg, overlay) -> overlay || !shouldHide(msg.getString()));

        // Read payment lines from normal chat only (shown or hidden)
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (overlay) return;
            trackPay(msg.getString());
        });
        ClientReceiveMessageEvents.GAME_CANCELED.register((msg, overlay) -> {
            if (overlay) return;
            trackPay(msg.getString());
        });

        // On-screen text, win streak box and payment popups
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("paydash", "stream"), (g, dt) -> renderStream(g));

        // Open the screen on the next tick (after the chat box has closed)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (aucActive && aucCutOff && System.currentTimeMillis() >= aucEnd) finishAuction();
            if (pendingPay != null && (client.screen == null || pendingTest)) {
                String[] pp = pendingPay;
                boolean tst = pendingTest;
                pendingPay = null;
                pendingTest = false;
                client.setScreen(new PayConfirmScreen(pp[0], pp[1], pp[2], tst));
            }
            if (openRequested && client.screen == null) {
                openRequested = false;
                client.setScreen(new DashScreen());
            }
        });
    }

    // {Boolean in, String name, String amount text} for a payment line, or null
    static Object[] payMatch(String text) {
        String t = text.trim();
        Matcher m = null;
        boolean in = true;
        for (Pattern p : IN) {
            Matcher x = p.matcher(t);
            if (x.find()) { m = x; break; }
        }
        if (m == null) {
            in = false;
            for (Pattern p : OUT) {
                Matcher x = p.matcher(t);
                if (x.find()) { m = x; break; }
            }
        }
        if (m == null) return null;
        return new Object[]{in, m.group("name"), m.group("num") + (m.group("suf") == null ? "" : m.group("suf"))};
    }

    static Pay parsePay(String text) {
        Object[] pm = payMatch(text);
        if (pm == null) return null;
        Double v = amount((String) pm[2]);
        if (v == null) return null;
        return new Pay(System.currentTimeMillis(), (Boolean) pm[0], v, (String) pm[1]);
    }

    // Recolors a payment line in chat (and adds the tax on payments you send). Returns the line unchanged if it is not a payment.
    static Component restyle(Component msg) {
        if (!ccOn && !taxShow) return msg;
        String text = msg.getString();
        Object[] pm = payMatch(text);
        if (pm == null) return msg;
        boolean in = (Boolean) pm[0];
        String name = (String) pm[1], amt = (String) pm[2];
        int ni = text.indexOf(name);
        int ai = text.indexOf(amt, Math.max(0, text.indexOf('$')));
        if (ni < 0 || ai < 0 || (ai >= ni && ai < ni + name.length()) || (ni >= ai && ni < ai + amt.length())) return msg;
        Style nameSt = ccOn ? Style.EMPTY.withColor(col(K_NAME) & 0xFFFFFF).withBold(ccBoldNames) : Style.EMPTY;
        Style amtSt = ccOn ? Style.EMPTY.withColor(col(in ? K_RECV : K_SENT) & 0xFFFFFF).withBold(ccBoldAmts) : Style.EMPTY;
        boolean nameFirst = ni < ai;
        int s1 = nameFirst ? ni : ai, l1 = nameFirst ? name.length() : amt.length();
        int s2 = nameFirst ? ai : ni, l2 = nameFirst ? amt.length() : name.length();
        MutableComponent out = Component.literal(text.substring(0, s1));
        out.append(Component.literal(text.substring(s1, s1 + l1)).withStyle(nameFirst ? nameSt : amtSt));
        out.append(Component.literal(text.substring(s1 + l1, s2)));
        out.append(Component.literal(text.substring(s2, s2 + l2)).withStyle(nameFirst ? amtSt : nameSt));
        out.append(Component.literal(text.substring(s2 + l2)));
        if (!in && taxShow) {
            Double v = amount(amt);
            if (v != null) {
                out.append(Component.literal(" (+" + money2(v * taxPct / 1000.0) + " tax)")
                        .withStyle(taxColor ? Style.EMPTY.withColor(col(K_TAX) & 0xFFFFFF) : Style.EMPTY));
            }
        }
        return out;
    }

    static Pay trackPay(String text) {
        Pay pay = parsePay(text);
        if (pay == null) return null;
        if (!pay.in() && taxCount) pay = new Pay(pay.ts(), false, pay.amount() * (1 + taxPct / 1000.0), pay.name());   // count the tax too
        PAYS.add(pay);
        boolean in = pay.in();
        double v = pay.amount();
        if (in && aucActive && (!aucCutOff || System.currentTimeMillis() <= aucEnd) && v > 0 && v >= AUC_MINS[aucMinIdx]) {
            aucBidders.add(pay.name().toLowerCase());
            if (v > aucTopBid) { aucTopBid = v; aucTop = pay.name(); }   // strictly higher: a tie stays with the first payer
        }
        if (pupOn && v >= PUP_MINS[pupMinIdx] && (in || pupSent)) addPopup(pay.name(), v, in);
        while (PAYS.size() > 5000) PAYS.remove(0);
        save();
        return pay;
    }

    // ---------- saving ----------
    static final Path CFG = FabricLoader.getInstance().getConfigDir().resolve("paydash.txt");

    static void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("s,").append(sessionStart).append('\n');
        sb.append("t,").append(theme).append('\n');
        sb.append("c,").append(aucItem.replace(',', ';')).append(',').append(aucAmount).append(',').append(aucTimeIdx).append(',').append(aucMinIdx).append(',').append(aucCutOff ? 1 : 0).append('\n');
        sb.append("x,").append(ovOn ? 1 : 0).append(',').append(ovRight ? 1 : 0).append(',').append(ovSizeIdx).append(',').append(wsOn ? 1 : 0).append(',')
          .append(wsStreak).append(',').append(wsBest).append(',').append(pupOn ? 1 : 0).append(',').append(pupSent ? 1 : 0).append(',')
          .append(pupMinIdx).append(',').append(pupSecIdx).append(',').append(hideOn ? 1 : 0).append(',').append(hideRecv ? 1 : 0).append(',')
          .append(hideSent ? 1 : 0).append(',').append(hideMinIdx).append('\n');
        sb.append("y,").append(wsAccentBar ? 1 : 0).append(',').append(wsBorder ? 1 : 0).append(',').append(wsSizeIdx).append(',').append(wsOpacity).append(',')
          .append(pupAnim).append(',').append(pupOpacity).append(',').append(pupBorder).append(',').append(pupGlow).append(',').append(pupTimerBar ? 1 : 0).append(',')
          .append(pupPlus ? 1 : 0).append(',').append(sndOn ? 1 : 0).append(',').append(sndIdx).append(',').append(sndVol).append(',').append(sndPitch).append(',')
          .append(sndRepeat).append(',').append(sndQueued ? 1 : 0).append(',').append(CI[K_GAIN]).append(',').append(CI[K_LOSS]).append(',').append(CI[K_ACCENT]).append(',')
          .append(CI[K_OVERLAY]).append(',').append(CI[K_LABEL]).append(',').append(CI[K_NUMBER]).append('\n');
        int[] wv = {wsSeparate ? 1 : 0, wsCmds ? 1 : 0, phOn ? 1 : 0, phAccent ? 1 : 0, phBorder ? 1 : 0, phSizeIdx, phOpacity, phPos, pupSparkles, pupSizeIdx,
                pupFace ? 1 : 0, pupRainbow ? 1 : 0, pupShine ? 1 : 0, pupCountUp ? 1 : 0, pupBounce ? 1 : 0, ccOn ? 1 : 0, ccBoldNames ? 1 : 0, ccBoldAmts ? 1 : 0,
                taxColor ? 1 : 0, taxShow ? 1 : 0, taxCount ? 1 : 0, taxPct, pcOn ? 1 : 0, pcRainbow ? 1 : 0, pcMinIdx, pcDelay, pcDim, cnfSndOn ? 1 : 0, cnfSndIdx};
        sb.append("w");
        for (int q : wv) sb.append(',').append(q);
        sb.append('\n').append("k");
        for (int q : CI) sb.append(',').append(q);
        sb.append('\n');
        for (int i = 0; i < OV_LINES.length; i++) sb.append("o,").append(i).append(',').append(OV_LINES[i].replace(',', ';')).append('\n');
        for (Auc au : AUCS)
            sb.append("a,").append(au.ts()).append(',').append(au.amount()).append(',').append(au.bid()).append(',').append(au.bidders()).append(',').append(au.timeIdx()).append(',')
              .append(au.item().replace(',', ';')).append(',').append(au.winner()).append('\n');
        for (Pay p : PAYS)
            sb.append("p,").append(p.ts()).append(',').append(p.in() ? 1 : 0).append(',').append(p.amount()).append(',').append(p.name()).append('\n');
        try { Files.writeString(CFG, sb.toString()); } catch (Exception ignored) { }
    }

    static void load() {
        try {
            for (String line : Files.readAllLines(CFG)) {
                try {
                    String[] p = line.split(",");
                    if (p[0].equals("s")) sessionStart = Long.parseLong(p[1]);
                    else if (p[0].equals("t")) theme = Math.max(0, Math.min(THEME_NAMES.length - 1, Integer.parseInt(p[1])));
                    else if (p[0].equals("x")) {
                        ovOn = p[1].equals("1"); ovRight = p[2].equals("1");
                        ovSizeIdx = Math.max(0, Math.min(OV_SIZES.length - 1, Integer.parseInt(p[3])));
                        wsOn = p[4].equals("1"); wsStreak = Integer.parseInt(p[5]); wsBest = Integer.parseInt(p[6]);
                        pupOn = p[7].equals("1"); pupSent = p[8].equals("1");
                        pupMinIdx = Math.max(0, Math.min(PUP_MINS.length - 1, Integer.parseInt(p[9])));
                        pupSecIdx = Math.max(0, Math.min(PUP_SECS.length - 1, Integer.parseInt(p[10])));
                        hideOn = p[11].equals("1"); hideRecv = p[12].equals("1"); hideSent = p[13].equals("1");
                        hideMinIdx = Math.max(0, Math.min(HIDE_MINS.length - 1, Integer.parseInt(p[14])));
                    }
                    else if (p[0].equals("y")) {
                        wsAccentBar = p[1].equals("1"); wsBorder = p[2].equals("1");
                        wsSizeIdx = clampI(Integer.parseInt(p[3]), 0, OV_SIZES.length - 1); wsOpacity = clampI(Integer.parseInt(p[4]), 10, 100);
                        pupAnim = clampI(Integer.parseInt(p[5]), 0, PUP_ANIMS.length - 1); pupOpacity = clampI(Integer.parseInt(p[6]), 50, 100);
                        pupBorder = clampI(Integer.parseInt(p[7]), 0, 3); pupGlow = clampI(Integer.parseInt(p[8]), 0, 100);
                        pupTimerBar = p[9].equals("1"); pupPlus = p[10].equals("1"); sndOn = p[11].equals("1");
                        sndIdx = clampI(Integer.parseInt(p[12]), 0, SND_NAMES.length - 1); sndVol = clampI(Integer.parseInt(p[13]), 10, 100);
                        sndPitch = clampI(Integer.parseInt(p[14]), 5, 20); sndRepeat = clampI(Integer.parseInt(p[15]), 1, 5); sndQueued = p[16].equals("1");
                        CI[K_GAIN] = clampI(Integer.parseInt(p[17]), 0, PAL.length - 1); CI[K_LOSS] = clampI(Integer.parseInt(p[18]), 0, PAL.length - 1);
                        CI[K_ACCENT] = clampI(Integer.parseInt(p[19]), 0, PAL.length - 1); CI[K_OVERLAY] = clampI(Integer.parseInt(p[20]), 0, PAL.length - 1);
                        CI[K_LABEL] = clampI(Integer.parseInt(p[21]), 0, PAL.length - 1); CI[K_NUMBER] = clampI(Integer.parseInt(p[22]), 0, PAL.length - 1);
                    }
                    else if (p[0].equals("w")) {
                        wsSeparate = p[1].equals("1"); wsCmds = p[2].equals("1"); phOn = p[3].equals("1"); phAccent = p[4].equals("1"); phBorder = p[5].equals("1");
                        phSizeIdx = clampI(Integer.parseInt(p[6]), 0, OV_SIZES.length - 1); phOpacity = clampI(Integer.parseInt(p[7]), 10, 100);
                        phPos = clampI(Integer.parseInt(p[8]), 0, PH_POS.length - 1); pupSparkles = clampI(Integer.parseInt(p[9]), 0, 20);
                        pupSizeIdx = clampI(Integer.parseInt(p[10]), 0, OV_SIZES.length - 1);
                        pupFace = p[11].equals("1"); pupRainbow = p[12].equals("1"); pupShine = p[13].equals("1"); pupCountUp = p[14].equals("1"); pupBounce = p[15].equals("1");
                        ccOn = p[16].equals("1"); ccBoldNames = p[17].equals("1"); ccBoldAmts = p[18].equals("1"); taxColor = p[19].equals("1"); taxShow = p[20].equals("1"); taxCount = p[21].equals("1");
                        taxPct = clampI(Integer.parseInt(p[22]), 0, 100); pcOn = p[23].equals("1"); pcRainbow = p[24].equals("1");
                        pcMinIdx = clampI(Integer.parseInt(p[25]), 0, PC_MINS.length - 1); pcDelay = clampI(Integer.parseInt(p[26]), 0, 30); pcDim = clampI(Integer.parseInt(p[27]), 0, 90);
                        cnfSndOn = p[28].equals("1"); cnfSndIdx = clampI(Integer.parseInt(p[29]), 0, SND_NAMES.length - 1);
                    }
                    else if (p[0].equals("k")) {
                        for (int i = 0; i < CI.length && i + 1 < p.length; i++) CI[i] = clampI(Integer.parseInt(p[i + 1]), 0, PAL.length - 1);
                    }
                    else if (p[0].equals("o")) { int n = Integer.parseInt(p[1]); if (n >= 0 && n < OV_LINES.length) OV_LINES[n] = p.length > 2 ? p[2] : ""; }
                    else if (p[0].equals("c")) { aucItem = p[1]; aucAmount = Integer.parseInt(p[2]); aucTimeIdx = Integer.parseInt(p[3]); aucMinIdx = Integer.parseInt(p[4]); if (p.length > 5) aucCutOff = p[5].equals("1"); }
                    else if (p[0].equals("a")) AUCS.add(new Auc(Long.parseLong(p[1]), p[6], Integer.parseInt(p[2]), p[7], Double.parseDouble(p[3]), Integer.parseInt(p[4]), Math.max(0, Math.min(AUC_TIMES.length - 1, Integer.parseInt(p[5])))));
                    else if (p[0].equals("p")) PAYS.add(new Pay(Long.parseLong(p[1]), p[2].equals("1"), Double.parseDouble(p[3]), p[4]));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    // ---------- drawing helpers ----------
    static void rrect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x + 2, y, x + w - 2, y + 1, c);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, c);
        g.fill(x, y + 2, x + w, y + h - 2, c);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, c);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, c);
    }

    // Area chart of a running total: line, soft fill toward the zero line, dashed zero line.
    static void chart(GuiGraphicsExtractor g, int x, int y, int w, int h, List<Double> pts) {
        int n = pts.size();
        double min = 0, max = 0;
        for (double q : pts) { min = Math.min(min, q); max = Math.max(max, q); }
        if (max - min < 1) max = min + 1;
        int yz = y + (int) (max / (max - min) * (h - 2));
        for (int dx = 0; dx < w; dx += 4) g.fill(x + dx, yz, x + Math.min(w, dx + 2), yz + 1, 0x33FFFFFF);
        if (n < 2) return;
        int prev = -1;
        for (int i = 0; i < w; i++) {
            double t = (double) i * (n - 1) / (w - 1);
            int i0 = (int) Math.floor(t);
            int i1 = Math.min(n - 1, i0 + 1);
            double val = pts.get(i0) + (pts.get(i1) - pts.get(i0)) * (t - i0);
            int yy = y + (int) ((max - val) / (max - min) * (h - 2));
            boolean up = val >= 0;
            int rgb = (up ? GREEN : RED) & 0xFFFFFF;
            int from = up ? yy + 1 : yz + 1, to = up ? yz : yy, span = to - from;
            if (span > 0) {
                int bands = Math.min(5, span);
                for (int b = 0; b < bands; b++) {
                    double k = up ? (double) b / bands : (double) (bands - 1 - b) / bands;
                    int a = (int) (0x2A * (1.0 - k));
                    int y0 = from + b * span / bands, y1 = from + (b + 1) * span / bands;
                    if (y1 > y0) g.fill(x + i, y0, x + i + 1, y1, (a << 24) | rgb);
                }
            }
            int lo = prev < 0 ? yy : Math.min(prev, yy), hi = prev < 0 ? yy : Math.max(prev, yy);
            g.fill(x + i, lo, x + i + 1, hi + 1, 0xC0000000 | rgb);
            prev = yy;
        }
    }

    // a pill shape (rounded all the way) and a round dot, for the on/off switches
    static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        double r = h / 2.0;
        for (int row = 0; row < h; row++) {
            double dy = row + 0.5 - r;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(x + inset, y + row, x + w - inset, y + row + 1, c);
        }
    }

    static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int c) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, c);
        }
    }

    static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + 1, c);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    static void hill(GuiGraphicsExtractor g, int x, int y, int w, int h, int baseY, int amp, double freq, double phase, int color) {
        for (int i = 0; i < w; i++) {
            int top = baseY - (int) (amp * (0.5 + 0.5 * Math.sin(i * freq + phase)));
            g.fill(x + i, top, x + i + 1, y + h, color);
        }
    }

    static void stars(GuiGraphicsExtractor g, int x, int y, int w, int h, int n, int seed, int alpha) {
        java.util.Random r = new java.util.Random(seed);
        for (int i = 0; i < n; i++) {
            int sx = x + r.nextInt(w), sy = y + r.nextInt(Math.max(1, h * 6 / 10));
            g.fill(sx, sy, sx + 1, sy + 1, (alpha << 24) | 0xFFFFFF);
        }
    }

    static void backdrop(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int[] t = THEME_COL[theme];
        switch (t[2]) {
            case 1 -> winter(g, x, y, w, h, t[0], t[1]);
            case 2 -> aurora(g, x, y, w, h, t[0], t[1]);
            case 3 -> city(g, x, y, w, h, t[0], t[1]);
            case 4 -> sunset(g, x, y, w, h, t[0], t[1]);
            case 5 -> space(g, x, y, w, h, t[0], t[1]);
            case 6 -> dunes(g, x, y, w, h, t[0], t[1]);
            case 7 -> xmasTree(g, x, y, w, h, t[0], t[1]);
            case 8 -> cabin(g, x, y, w, h, t[0], t[1]);
            case 9 -> candy(g, x, y, w, h, t[0], t[1]);
            case 10 -> snowman(g, x, y, w, h, t[0], t[1]);
            default -> forest(g, x, y, w, h, t[0], t[1]);
        }
    }

    // Christmas tree with lights, a star and a few gifts, in falling snow.
    static void xmasTree(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        stars(g, x, y, w, h * 10 / 6, 110, 13, 0xB0);
        hill(g, x, y, w, h, y + h - 14, 8, 0.03, 0.5, 0xFF5E78AC);
        int cx = x + w * 72 / 100, base = y + h - 14, th = 128, tw = 76, top = base - th;
        java.util.Random r = new java.util.Random(31);
        for (int row = 0; row < th; row++) {
            int tier = row / 32, inTier = row % 32;
            int half = (int) ((tw / 2.0) * ((tier + 1) / 4.0 + 0.24 * inTier / 32.0)) + 2;
            g.fill(cx - half, top + row, cx + half + 1, top + row + 1, row % 2 == 0 ? 0xFF0E5A2C : 0xFF0A4A24);
        }
        g.fill(cx - 4, base - 6, cx + 5, base, 0xFF4A2A14);
        disc(g, cx, top - 2, 9, 0x30FFD84A);
        disc(g, cx, top - 2, 4, 0xFFFFD84A);
        int[] lc = {0xFFFF4A5A, 0xFFFFD84A, 0xFF58B8FF, 0xFFFF8AC8};
        for (int i = 0; i < 34; i++) {
            int row = 8 + r.nextInt(th - 14), tier = row / 32, inTier = row % 32;
            int half = (int) ((tw / 2.0) * ((tier + 1) / 4.0 + 0.24 * inTier / 32.0));
            int lx = cx - half + 3 + r.nextInt(Math.max(1, 2 * half - 6));
            int col = lc[i % 4];
            g.fill(lx, top + row, lx + 3, top + row + 3, col);
            g.fill(lx - 1, top + row - 1, lx + 4, top + row + 4, (0x30 << 24) | (col & 0xFFFFFF));
        }
        int[][] gifts = {{-70, 16, 14, 0xFFC81E3A}, {-52, 12, 12, 0xFF1E7AC8}, {-34, 18, 16, 0xFF1E9A4A}, {40, 14, 12, 0xFFC8A21E}};
        for (int[] gf : gifts) {
            int gx = cx + gf[0], gw = gf[1], gh = gf[2];
            g.fill(gx, base - gh, gx + gw, base, gf[3]);
            g.fill(gx + gw / 2 - 1, base - gh, gx + gw / 2 + 1, base, 0xFFF2F4FA);
            g.fill(gx, base - gh / 2 - 1, gx + gw, base - gh / 2 + 1, 0xFFF2F4FA);
        }
    }

    // A snowy cabin with glowing windows, a smoking chimney and a few pines.
    static void cabin(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        stars(g, x, y, w, h * 10 / 6, 100, 17, 0xB0);
        disc(g, x + w - 64, y + 52, 9, 0xFFE8EEFF);
        hill(g, x, y, w, h, y + h - 52, 24, 0.016, 0.8, 0xFF3A4C86);
        hill(g, x, y, w, h, y + h - 26, 16, 0.024, 2.4, 0xFF2A3A6C);
        int cx = x + w * 30 / 100, base = y + h - 30;
        g.fill(cx - 28, base - 34, cx + 28, base, 0xFF3A2414);
        for (int row = 0; row < 22; row++) {
            int half = 4 + row * 34 / 22;
            g.fill(cx - half, base - 56 + row, cx + half + 1, base - 55 + row, row < 4 ? 0xFFE8EEFF : 0xFF241408);
        }
        g.fill(cx + 14, base - 56, cx + 22, base - 38, 0xFF2A1A0C);
        for (int i = 0; i < 5; i++) disc(g, cx + 18 + i * 3, base - 62 - i * 8, 3 + i, 0x28C8D4F0);
        g.fill(cx - 24, base - 26, cx - 12, base - 14, 0x30FFC857);
        g.fill(cx - 22, base - 24, cx - 14, base - 16, 0xFFFFC857);
        g.fill(cx + 12, base - 26, cx + 24, base - 14, 0x30FFC857);
        g.fill(cx + 14, base - 24, cx + 22, base - 16, 0xFFFFC857);
        g.fill(cx - 5, base - 20, cx + 5, base, 0xFF1A0E06);
        for (int px2 : new int[]{cx - 80, cx - 62, cx + 60, cx + 80}) {
            for (int row = 0; row < 34; row++) {
                int half = 1 + row / 3;
                g.fill(px2 - half, base - 34 + row, px2 + half + 1, base - 33 + row, 0xFF0A2A1A);
            }
        }
    }

    // Red and white candy canes on a red sky.
    static void candy(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        for (int sx = 0; sx < w; sx += 36) g.fill(x + sx, y, x + sx + 18, y + h, 0x10FFFFFF);
        stars(g, x, y, w, h * 10 / 6, 80, 29, 0xA0);
        hill(g, x, y, w, h, y + h - 18, 8, 0.03, 0.2, 0xFF9A4A58);
        int[] cxs = {w * 20 / 100, w * 52 / 100, w * 82 / 100}, hs = {74, 94, 62};
        for (int k = 0; k < 3; k++) {
            int cx = x + cxs[k], base = y + h - 12, hg = hs[k];
            for (int yy = 0; yy < hg; yy++) g.fill(cx - 3, base - yy - 1, cx + 3, base - yy, (yy / 6) % 2 == 0 ? 0xFFF4F4F8 : 0xFFD81E3A);
            for (int deg = 180; deg >= 0; deg -= 6) {
                double a = Math.toRadians(deg);
                int hx = cx + 9 + (int) Math.round(9 * Math.cos(a)), hy = base - hg - (int) Math.round(9 * Math.sin(a));
                g.fill(hx - 3, hy - 3, hx + 3, hy + 3, (deg / 24) % 2 == 0 ? 0xFFF4F4F8 : 0xFFD81E3A);
            }
        }
    }

    // A snowman on a snowy hill under the moon.
    static void snowman(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        stars(g, x, y, w, h * 10 / 6, 120, 37, 0xB0);
        disc(g, x + w * 25 / 100, y + 54, 9, 0xFFE8EEFF);
        hill(g, x, y, w, h, y + h - 54, 22, 0.017, 0.2, 0xFF5A74A8);
        hill(g, x, y, w, h, y + h - 26, 14, 0.026, 1.9, 0xFF7088BC);
        int cx = x + w * 74 / 100, base = y + h - 26;
        disc(g, cx, base - 14, 16, 0xFFF2F6FF);
        disc(g, cx, base - 40, 11, 0xFFF2F6FF);
        disc(g, cx, base - 58, 8, 0xFFF2F6FF);
        g.fill(cx - 3, base - 60, cx - 1, base - 58, 0xFF10131C);
        g.fill(cx + 2, base - 60, cx + 4, base - 58, 0xFF10131C);
        g.fill(cx, base - 57, cx + 6, base - 56, 0xFFFF8A2A);
        g.fill(cx - 9, base - 51, cx + 10, base - 48, 0xFFC81E3A);
        g.fill(cx - 7, base - 69, cx + 8, base - 65, 0xFF10131C);
        g.fill(cx - 4, base - 77, cx + 5, base - 69, 0xFF10131C);
        for (int b = 0; b < 3; b++) g.fill(cx - 1, base - 42 + b * 7, cx + 1, base - 40 + b * 7, 0xFF10131C);
        g.fill(cx - 24, base - 46, cx - 10, base - 44, 0xFF4A2A14);
        g.fill(cx + 11, base - 44, cx + 25, base - 42, 0xFF4A2A14);
        for (int px2 : new int[]{x + w * 12 / 100, x + w * 17 / 100, x + w * 92 / 100}) {
            for (int row = 0; row < 30; row++) {
                int half = 1 + row / 3;
                g.fill(px2 - half, base - 30 + row, px2 + half + 1, base - 29 + row, 0xFF0A2A1A);
            }
        }
    }

    // Stars only: a dense sky with a faint glow.
    static void space(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        stars(g, x, y, w, h * 10 / 6, 220, 3, 0xB0);
        stars(g, x, y, w, h * 10 / 6, 40, 9, 0xFF);
        disc(g, x + w * 70 / 100, y + h * 40 / 100, 30, 0x0CFFFFFF);
        disc(g, x + w * 70 / 100, y + h * 40 / 100, 18, 0x10FFFFFF);
    }

    // Layered dunes in darker shades of the theme colour.
    static void dunes(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        stars(g, x, y, w, h * 6 / 10, 28, 17, 0x70);
        hill(g, x, y, w, h, y + h - 62, 22, 0.018, 0.3, shade(c2, 0.55));
        hill(g, x, y, w, h, y + h - 36, 18, 0.028, 1.8, shade(c2, 0.32));
        hill(g, x, y, w, h, y + h - 16, 12, 0.04, 3.0, shade(c2, 0.16));
    }

    // Winter night: snow, a moon, hills, and a little sleigh with two reindeer.
    static void winter(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        stars(g, x, y, w, h, 90, 11, 0xB0);
        disc(g, x + w - 60, y + 56, 9, 0xFFE8EEFF);
        disc(g, x + w - 56, y + 53, 8, 0xFF16224A);
        int sx = x + w * 45 / 100, sy = y + 46, dark = 0xFF080C20;
        g.fill(sx, sy + 6, sx + 14, sy + 11, dark);          // sleigh
        g.fill(sx + 12, sy + 4, sx + 16, sy + 7, dark);
        g.fill(sx - 1, sy + 11, sx + 17, sy + 12, dark);
        g.fill(sx + 15, sy + 9, sx + 17, sy + 12, dark);
        g.fill(sx + 4, sy + 2, sx + 8, sy + 7, dark);        // rider
        g.fill(sx + 16, sy + 7, sx + 28, sy + 8, dark);      // reins
        for (int d = 0; d < 2; d++) {                         // reindeer
            int rx = sx + 26 + d * 15, ry = sy + 3 - d * 5;
            g.fill(rx, ry, rx + 8, ry + 3, dark);
            g.fill(rx + 1, ry + 3, rx + 2, ry + 6, dark);
            g.fill(rx + 6, ry + 3, rx + 7, ry + 6, dark);
            g.fill(rx + 8, ry - 2, rx + 11, ry + 1, dark);
            g.fill(rx + 9, ry - 4, rx + 10, ry - 2, dark);
        }
        hill(g, x, y, w, h, y + h - 46, 26, 0.017, 0.4, 0xFF1E2E5E);
        hill(g, x, y, w, h, y + h - 22, 18, 0.026, 2.1, 0xFF141F44);
    }

    // Aurora: soft green, blue and violet curtains over dark hills.
    static void aurora(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        stars(g, x, y, w, h, 70, 23, 0xA0);
        int[] cols = {0x3DF0A0, 0x59C8FF, 0xB07CFF};
        for (int b = 0; b < 3; b++) {
            for (int i = 0; i < w; i++) {
                int top = y + 28 + b * 16 + (int) (10 * Math.sin(i * 0.035 + b * 1.7) + 6 * Math.sin(i * 0.09 + b));
                for (int k = 0; k < 5; k++) {
                    int a = (int) (0x34 * (1.0 - k / 5.0));
                    g.fill(x + i, top + k * 9, x + i + 1, top + (k + 1) * 9, (a << 24) | cols[b]);
                }
            }
        }
        hill(g, x, y, w, h, y + h - 30, 22, 0.02, 1.0, 0xFF060310);
    }

    // City at night: a skyline with lit windows under a purple horizon.
    static void city(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        stars(g, x, y, w, h, 45, 5, 0x90);
        disc(g, x + w - 70, y + 54, 8, 0xFFF2E9FF);
        java.util.Random r = new java.util.Random(5);
        int cx = x - 4;
        while (cx < x + w) {
            int bw = 14 + r.nextInt(22), bh = 30 + r.nextInt(70), top = y + h - bh;
            g.fill(cx, top, cx + bw, y + h, 0xFF0A0716);
            for (int wy = top + 4; wy < y + h - 4; wy += 6)
                for (int wx = cx + 3; wx < cx + bw - 3; wx += 5)
                    if (r.nextInt(4) == 0) g.fill(wx, wy, wx + 2, wy + 3, r.nextBoolean() ? 0xB0FFD98A : 0x80FF8AC8);
            cx += bw + 1 + r.nextInt(3);
        }
    }

    // Sunset: a low sun with a glow and layered hills.
    static void sunset(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        stars(g, x, y, w, h * 6 / 10, 30, 41, 0x70);
        int sx = x + w * 62 / 100, sy = y + h - 76;
        for (int rr = 46; rr > 14; rr -= 8) disc(g, sx, sy, rr, 0x16FFB27A);
        disc(g, sx, sy, 14, 0xFFFFD08A);
        hill(g, x, y, w, h, y + h - 62, 20, 0.02, 0.0, 0xFF5A2466);
        hill(g, x, y, w, h, y + h - 36, 16, 0.03, 1.7, 0xFF2A0F3F);
        hill(g, x, y, w, h, y + h - 16, 10, 0.045, 3.1, 0xFF12071F);
    }

    // A night sky with snow and pine trees, drawn from simple shapes (original artwork).
    static void forest(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        g.fillGradient(x, y, x + w, y + h, c1, c2);
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 45; i++) {
            int sx = x + r.nextInt(w), sy = y + r.nextInt(h);
            g.fill(sx, sy, sx + 1, sy + 1, 0x90FFFFFF);
        }
        int base = y + h;
        int trees = Math.max(8, w / 13);
        for (int i = 0; i < trees; i++) {
            int tx = x + r.nextInt(w);
            int th = 30 + r.nextInt(50);
            int tw = 10 + th / 3;
            int top = base - th;
            int tier = Math.max(4, th / 4);
            for (int row = 0; row < th; row++) {
                int rr = row % tier;
                int rw = Math.max(1, (row * tw) / th + rr / 2);
                g.fill(tx - rw / 2, top + row, tx + rw / 2 + 1, top + row + 1, 0xE607030F);
            }
            g.fill(tx - 1, base - 3, tx + 2, base, 0xE607030F);
        }
    }

    // =====================================================================
    //  The dashboard screen
    // =====================================================================
    static int seg(GuiGraphicsExtractor g, Font f, String text, int x, int y, int color, boolean bold) {
        if (bold) txb(g, f, text, x, y, color); else tx(g, f, text, x, y, color);
        return bold ? twb(f, text) : tw(f, text);
    }

    // Two example chat lines, drawn with the current chat settings
    static void chatPreview(GuiGraphicsExtractor g, Font f, int x, int y) {
        int white = 0xFFFFFFFF;
        int nameC = ccOn ? col(K_NAME) : white, recvC = ccOn ? col(K_RECV) : white, sentC = ccOn ? col(K_SENT) : white;
        int cx = x;
        cx += seg(g, f, "ExamplePlayer", cx, y, nameC, ccOn && ccBoldNames);
        cx += seg(g, f, " paid you ", cx, y, white, false);
        seg(g, f, "$5B", cx, y, recvC, ccOn && ccBoldAmts);
        cx = x;
        int y2 = y + 11;
        cx += seg(g, f, "You paid ", cx, y2, white, false);
        cx += seg(g, f, "ExamplePlayer", cx, y2, nameC, ccOn && ccBoldNames);
        cx += seg(g, f, " ", cx, y2, white, false);
        cx += seg(g, f, "$1M", cx, y2, sentC, ccOn && ccBoldAmts);
        if (taxShow) seg(g, f, " (+$20K tax)", cx, y2, taxColor ? col(K_TAX) : white, false);
    }

    // The "are you sure?" screen for a big /pay. It never sends anything itself.
    static class PayConfirmScreen extends Screen {
        final String name, amtText, cmd;
        final boolean test;
        final long openedAt = System.currentTimeMillis();
        Button okBtn, noBtn;
        int bx, by;
        static final int BW = 230, BH = 128;

        PayConfirmScreen(String name, String amtText, String cmd, boolean test) {
            super(Component.literal("Confirm payment"));
            this.name = name;
            this.amtText = amtText;
            this.cmd = cmd;
            this.test = test;
            if (cnfSndOn) playSnd(cnfSndIdx);
        }

        @Override
        protected void init() {
            bx = (this.width - BW) / 2;
            by = (this.height - BH) / 2;
            okBtn = Button.builder(Component.empty(), b -> {
                if (System.currentTimeMillis() - openedAt < pcDelay * 100L) return;
                if (!test) {
                    payApprovedCmd = cmd;
                    payApprovedUntil = System.currentTimeMillis() + 30000;
                }
                onClose();
            }).bounds(bx + 12, by + BH - 30, 100, 18).build();
            noBtn = Button.builder(Component.empty(), b -> onClose()).bounds(bx + BW - 112, by + BH - 30, 100, 18).build();
            addRenderableWidget(okBtn);
            addRenderableWidget(noBtn);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            Font f = this.font;
            long now = System.currentTimeMillis();
            g.fill(0, 0, this.width, this.height, alpha(0xFF000000, pcDim / 100.0));
            int border = pcRainbow ? hue((now % 3000) / 3000f) : col(K_CACCENT);
            rrect(g, bx, by, BW, BH, col(K_CBG));
            frameN(g, bx, by, BW, BH, 2, border);
            spaced(g, f, "CONFIRM PAYMENT", bx + 12, by + 10, WHITE);
            small(g, f, "You are about to pay", bx + 12, by + 26, MUTED, false);
            txb(g, f, fit(f, name, BW - 24), bx + 12, by + 36, col(K_CNAME));
            Double v = amount(amtText);
            g.pose().pushMatrix();
            g.pose().translate(bx + 12, by + 50);
            g.pose().scale(1.6f, 1.6f);
            txb(g, f, v == null ? amtText : money2(v), 0, 0, col(K_CAMT));
            g.pose().popMatrix();
            if (taxShow && v != null) small(g, f, "+ " + money2(v * taxPct / 1000.0) + " tax (" + String.format(Locale.US, "%.1f", taxPct / 10.0) + "%)", bx + 12, by + 72, MUTED, false);
            small(g, f, "After CONFIRM: press T, then Up, then Enter to send it yourself.", bx + 12, by + 84, MUTED, false);
            boolean ready = now - openedAt >= pcDelay * 100L;
            okBtn.active = ready;
            rrect(g, bx + 12, by + BH - 30, 100, 18, ready ? col(K_CCONFIRM) : shade(col(K_CCONFIRM), 0.4));
            String ok = ready ? "CONFIRM" : "WAIT...";
            small(g, f, ok, bx + 12 + (100 - (int) (twb(f, ok) * 0.7f)) / 2, by + BH - 30 + 6, WHITE, true);
            rrect(g, bx + BW - 112, by + BH - 30, 100, 18, col(K_CCANCEL));
            small(g, f, "CANCEL", bx + BW - 112 + (100 - (int) (twb(f, "CANCEL") * 0.7f)) / 2, by + BH - 30 + 6, WHITE, true);
        }
    }

    static class DashScreen extends Screen {
        record Btn(Button w, String label, BooleanSupplier active, BooleanSupplier visible, boolean toggle, int vx, int vy, int vw, int vh) {}

        final List<Btn> btns = new ArrayList<>();
        int tab = 4;      // 0 = Payments, 1 = Players, 2 = Settings, 3 = Rakeback, 4 = Dashboard (opens first), 5 = Auctions
        int range = 4;    // 0 session, 1 24h, 2 7d, 3 30d, 4 all
        int filter = 0;   // 0 all, 1 in, 2 out
        int page = 0;
        int stSub = 0;               // Streamers: selected sub-tab
        boolean histOpen = false;   // Auctions: the history bar starts closed
        int px, py, pw, ph;
        float k = 1f, ox = 0f, oy = 0f;   // the whole window is drawn at the size that fits your screen (any GUI scale)

        DashScreen() {
            super(Component.literal("Snowy's Client"));
        }

        void add(String label, int x, int y, int w, int h, BooleanSupplier active, BooleanSupplier visible, Runnable action) {
            Button b = Button.builder(Component.empty(), btn -> { if (visible.getAsBoolean()) action.run(); })
                    .bounds(Math.round(ox + x * k), Math.round(oy + y * k), Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k))).build();
            addRenderableWidget(b);
            btns.add(new Btn(b, label, active, visible, false, x, y, w, h));
        }

        void addToggle(int x, int y, int w, int h, BooleanSupplier state, BooleanSupplier visible, Runnable action) {
            Button b = Button.builder(Component.empty(), btn -> { if (visible.getAsBoolean()) action.run(); })
                    .bounds(Math.round(ox + x * k), Math.round(oy + y * k), Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k))).build();
            addRenderableWidget(b);
            btns.add(new Btn(b, "", state, visible, true, x, y, w, h));
        }

        @Override
        protected void init() {
            btns.clear();
            k = Math.max(0.7f, Math.min(2.2f, Math.min((this.width - 16) / 520f, (this.height - 16) / 270f)));
            pw = 520;
            ph = 270;
            px = 0;
            py = 0;
            ox = (this.width - 520 * k) / 2f;
            oy = (this.height - 270 * k) / 2f;

            add("DASHBOARD", px + 8, py + 44, 64, 16, () -> tab == 4, () -> true, () -> { tab = 4; page = 0; });
            add("PAYMENTS", px + 8, py + 64, 64, 16, () -> tab == 0, () -> true, () -> { tab = 0; page = 0; });
            add("PLAYERS", px + 8, py + 84, 64, 16, () -> tab == 1, () -> true, () -> { tab = 1; page = 0; });
            add("RAKEBACK", px + 8, py + 104, 64, 16, () -> tab == 3, () -> true, () -> { tab = 3; page = 0; });
            add("AUCTIONS", px + 8, py + 124, 64, 16, () -> tab == 5, () -> true, () -> { tab = 5; page = 0; });
            add("STREAMERS", px + 8, py + 184, 64, 16, () -> tab == 7, () -> true, () -> { tab = 7; page = 0; });
            add("THEMES", px + 8, py + 164, 64, 16, () -> tab == 6, () -> true, () -> { tab = 6; page = 0; });
            add("SETTINGS", px + 8, py + 144, 64, 16, () -> tab == 2, () -> true, () -> { tab = 2; page = 0; });

            // time range, right-aligned next to the reset button
            String[] names = {"SESSION", "24H", "7D", "30D", "ALL"};
            int[] widths = {44, 28, 24, 28, 24};
            int total = 0;
            for (int w : widths) total += w + 3;
            int rx = px + pw - 92 - total;
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                add(names[i], rx, py + 8, widths[i], 14, () -> range == idx, () -> tab != 2 && tab != 5 && tab != 3 && tab != 6 && tab != 7, () -> { range = idx; page = 0; });
                rx += widths[i] + 3;
            }
            add("RESET SESSION", px + pw - 88, py + 8, 80, 14, () -> false, () -> tab != 2 && tab != 5 && tab != 3 && tab != 6 && tab != 7, () -> { sessionStart = System.currentTimeMillis(); save(); page = 0; });

            // history filter (Payments tab only)
            int fx = px + pw - 8 - (30 + 3 + 24 + 3 + 28);
            add("ALL", fx, py + 86, 30, 13, () -> filter == 0, () -> tab == 0, () -> { filter = 0; page = 0; });
            add("IN", fx + 33, py + 86, 24, 13, () -> filter == 1, () -> tab == 0, () -> { filter = 1; page = 0; });
            add("OUT", fx + 60, py + 86, 28, 13, () -> filter == 2, () -> tab == 0, () -> { filter = 2; page = 0; });

            // paging
            add("<", px + pw - 62, py + ph - 20, 18, 13, () -> false, () -> tab != 2 && tab != 4 && tab != 5 && tab != 6 && tab != 7, () -> { if (page > 0) page--; });
            add(">", px + pw - 26, py + ph - 20, 18, 13, () -> false, () -> tab != 2 && tab != 4 && tab != 5 && tab != 6 && tab != 7, () -> page++);

            // auction controls
            int ax0 = px + 86, ax1 = px + pw - 8, amw = ax1 - ax0;
            int aMainW = (amw - 6) * 60 / 100, aSetW = amw - 6 - aMainW;
            int asx = ax0 + aMainW + 6, asr = asx + aSetW;
            int aTop = py + 84;
            BooleanSupplier ready = () -> tab == 5 && !aucActive && !aucEnded;
            add("-", asr - 62, aTop + 33, 14, 12, () -> false, ready, () -> { aucAmount = Math.max(1, aucAmount - 1); save(); });
            add("+", asr - 22, aTop + 33, 14, 12, () -> false, ready, () -> { aucAmount = Math.min(64, aucAmount + 1); save(); });
            add("-", asr - 62, aTop + 48, 14, 12, () -> false, ready, () -> { aucTimeIdx = Math.max(0, aucTimeIdx - 1); save(); });
            add("+", asr - 22, aTop + 48, 14, 12, () -> false, ready, () -> { aucTimeIdx = Math.min(AUC_TIMES.length - 1, aucTimeIdx + 1); save(); });
            add("-", asr - 62, aTop + 63, 14, 12, () -> false, ready, () -> { aucMinIdx = Math.max(0, aucMinIdx - 1); save(); });
            add("+", asr - 22, aTop + 63, 14, 12, () -> false, ready, () -> { aucMinIdx = Math.min(AUC_MINS.length - 1, aucMinIdx + 1); save(); });
            addToggle(asr - 8 - 34, aTop + 79, 34, 12, () -> aucCutOff, ready, () -> { aucCutOff = !aucCutOff; save(); });
            add("START AUCTION", asx + 8, aTop + 95, aSetW - 16, 12, () -> false, ready, () -> startAuction());
            add("FINISH", asx + 8, aTop + 95, aSetW - 16, 12, () -> false, () -> tab == 5 && aucActive, () -> finishAuction());
            add("NEW AUCTION", asx + 8, aTop + 95, aSetW - 16, 12, () -> false, () -> tab == 5 && aucEnded && !aucActive, () -> { aucEnded = false; });
            int hyI = aTop + 110 + 8;
            add("SHOW", ax1 - 38, hyI - 2, 34, 12, () -> false, () -> tab == 5 && !histOpen, () -> histOpen = true);
            add("HIDE", ax1 - 38, hyI - 2, 34, 12, () -> false, () -> tab == 5 && histOpen, () -> histOpen = false);
            // recent rounds: click one to fill the setup
            int chipW = Math.min(110, (amw - 12) / 3);
            for (int i = 0; i < 3; i++) {
                final int ci = i;
                add("#chip", ax0 + i * (chipW + 6), py + 64, chipW, 14, () -> false, () -> tab == 5 && !aucActive && ci < AUCS.size(), () -> {
                    Auc au = AUCS.get(ci);
                    aucItem = au.item();
                    aucAmount = au.amount();
                    aucTimeIdx = au.timeIdx();
                    aucEnded = false;
                    save();
                });
            }

            // theme grid (Themes page): one invisible button per theme
            int gx0 = px + 86, gmw = pw - 94, gCell = (gmw - 8) / 3;
            for (int i = 0; i < THEME_NAMES.length; i++) {
                final int ti = i;
                add("#theme", gx0 + (i % 3) * (gCell + 4), py + 54 + (i / 3) * 21, gCell, 18, () -> false, () -> tab == 6, () -> { theme = ti; save(); });
            }
            // streamers: the sub-tab list and the rows of every sub-tab
            int smx0 = px + 86, smw = pw - 94, scx0 = smx0 + 76, scwt = smw - 76, srowsTop = py + 72;
            for (int k = 0; k < SUB_NAMES.length; k++) {
                final int sk = k;
                add("#sub", smx0, py + 54 + k * 19, 70, 16, () -> false, () -> tab == 7, () -> stSub = sk);
            }
            for (int k = 0; k < SUBS.size(); k++) {
                final int sk = k;
                BooleanSupplier svis = () -> tab == 7 && stSub == sk;
                List<Row> rs = SUBS.get(k);
                int[][] lay = layout(rs, scx0, srowsTop, scwt);
                for (int i = 0; i < rs.size(); i++) {
                    Row r = rs.get(i);
                    int[] bb = lay[i];
                    switch (r.type()) {
                        case 0 -> addToggle(bb[0] + bb[2] - 6 - 36, bb[1] + 2, 36, 12, r.on(), svis, r.plus());
                        case 1 -> {
                            add("-", bb[0] + bb[2] - 66, bb[1] + 2, 14, 12, () -> false, svis, r.minus());
                            add("+", bb[0] + bb[2] - 20, bb[1] + 2, 14, 12, () -> false, svis, r.plus());
                        }
                        case 2 -> {
                            add("<", bb[0] + 4, bb[1] + 2, 12, 12, () -> false, svis, r.minus());
                            add(">", bb[0] + bb[2] - 16, bb[1] + 2, 12, 12, () -> false, svis, r.plus());
                        }
                        case 3 -> add("#swatch", bb[0] + bb[2] - 6 - 18, bb[1] + 2, 18, 12, () -> false, svis, r.plus());
                        default -> add(r.label(), bb[0], bb[1], bb[2], bb[3], () -> false, svis, r.plus());
                    }
                }
            }
            // settings controls: one switch per card
            int sx1 = px + pw - 8;
            int[] hs = {1, 2, 0};   // card order: profit tracker, rakeback, balance
            for (int i = 0; i < 3; i++) {
                final int hi = hs[i];
                addToggle(sx1 - 8 - 36, py + 71 + i * 40, 36, 12, () -> hudState(hi), () -> tab == 2, () -> { setHud(hi, !hudState(hi)); saveCtl(); });
            }
        }

        long cutoff() {
            long now = System.currentTimeMillis();
            return switch (range) {
                case 0 -> sessionStart;
                case 1 -> now - 24L * 3600_000L;
                case 2 -> now - 7L * 24 * 3600_000L;
                case 3 -> now - 30L * 24 * 3600_000L;
                default -> 0L;
            };
        }

        List<Pay> view() {
            long cut = cutoff();
            List<Pay> out = new ArrayList<>();
            for (int i = PAYS.size() - 1; i >= 0; i--) {   // newest first
                Pay p = PAYS.get(i);
                if (p.ts() >= cut) out.add(p);
            }
            return out;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            g.enableScissor((int) ox, (int) oy, (int) Math.ceil(ox + pw * k), (int) Math.ceil(oy + ph * k));
            g.pose().pushMatrix();
            g.pose().translate(ox, oy);
            g.pose().scale(k, k);
            backdrop(g, px, py, pw, ph);
            g.pose().popMatrix();
            g.disableScissor();
            g.pose().pushMatrix();
            g.pose().translate(ox, oy);
            g.pose().scale(k, k);
            drawPage(g);
            g.pose().popMatrix();
        }

        void drawPage(GuiGraphicsExtractor g) {
            Font f = this.font;
            List<Pay> v = view();

            // window + sidebar
            frame(g, px, py, pw, ph, EDGE);
            g.fill(px + 1, py + 1, px + 78, py + ph - 1, SIDE);
            g.fillGradient(px + 80, py + 28, px + pw - 1, py + ph - 1, 0xC00A0614, 0x550A0614);   // keeps the text readable; the trees show through lower down

            spaced(g, f, "SNOWY'S", px + 8, py + 12, TITLE);
            spaced(g, f, "CLIENT", px + 8, py + 22, TITLE);

            int x0 = px + 86, x1 = px + pw - 8, mw = x1 - x0;

            // header of the page
            if (tab == 4) {
                // welcome banner: your own skin and name
                Minecraft m2 = Minecraft.getInstance();
                if (m2.player != null) PlayerFaceExtractor.extractRenderState(g, m2.player.getSkin(), x0, py + 28, 20);
                g.pose().pushMatrix();
                g.pose().translate(x0 + 26, py + 28);
                g.pose().scale(1.3f, 1.3f);
                txb(g, f, "Welcome,", 0, 0, WHITE);
                int nx = twb(f, "Welcome, ");
                for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) txb(g, f, "2amsnowy", nx + o[0], o[1], 0x40B48CFF);   // soft glow
                txb(g, f, "2amsnowy", nx, 0, 0xFFC6A6FF);
                g.pose().popMatrix();
                small(g, f, "Here is how your payments look.", x0 + 26, py + 43, MUTED, false);
            } else {
                spaced(g, f, tab == 7 ? "STREAMERS" : tab == 6 ? "THEMES" : tab == 5 ? "AUCTIONS" : tab == 4 ? "DASHBOARD" : (tab == 0 ? "PAYMENTS" : (tab == 1 ? "PLAYERS" : (tab == 3 ? "RAKEBACK" : "SETTINGS"))), x0, py + 28, WHITE);
            small(g, f, tab == 7 ? "Tools for your stream. Nothing here sends chat or commands." : tab == 6 ? "Pick a backdrop. Only the picture behind the panels changes." : tab == 5 ? "Payments you receive count as bids. The highest single payment wins." : tab == 4 ? "A quick look at your payments." : tab == 0 ? "Every payment you sent or received." : (tab == 1 ? "Everyone you have traded with." : (tab == 3 ? "Read from your balance HUD mod. Closest to the minimum first." : "Turn the balance HUD panels on or off.")), x0, py + 42, MUTED, false);
            }

            int rowsPer = Math.max(1, (ph - 140) / 14);
            int count;

            if (tab == 0) {
                // ---- cards ----
                double in = 0, out = 0;
                int nIn = 0, nOut = 0;
                for (Pay p : v) {
                    if (p.in()) { in += p.amount(); nIn++; } else { out += p.amount(); nOut++; }
                }
                int n = v.size();
                int cw = (mw - 18) / 4, cy = py + 54, ch = 28;
                card(g, f, x0, cy, cw, ch, "RECEIVED", money2(in), nIn + " payments", GREEN);
                card(g, f, x0 + cw + 6, cy, cw, ch, "SENT", out == 0 ? "$0" : "-" + money2(out), nOut + " payments", RED);
                card(g, f, x0 + 2 * (cw + 6), cy, cw, ch, "NET", signed(in - out), "received minus sent", in - out >= 0 ? GREEN : RED);
                card(g, f, x0 + 3 * (cw + 6), cy, cw, ch, "TRANSACTIONS", String.valueOf(n), n == 0 ? "no payments yet" : "avg " + money2((in + out) / n) + " each", TITLE);

                // ---- history ----
                spaced(g, f, "HISTORY", x0, py + 89, WHITE);
                int hy = py + 104;
                small(g, f, "PLAYER", x0 + 6, hy, MUTED, true);
                small(g, f, "TYPE", x0 + mw * 42 / 100, hy, MUTED, true);
                int amtRight = x0 + mw * 80 / 100;
                small(g, f, "AMOUNT", amtRight - (int) (twb(f, "AMOUNT") * 0.7f), hy, MUTED, true);
                small(g, f, "WHEN", x1 - 6 - (int) (twb(f, "WHEN") * 0.7f), hy, MUTED, true);
                g.fill(x0, hy + 8, x1, hy + 9, EDGE);

                List<Pay> rows = new ArrayList<>();
                for (Pay p : v) if (filter == 0 || (filter == 1 && p.in()) || (filter == 2 && !p.in())) rows.add(p);
                count = rows.size();
                int pages = Math.max(1, (count + rowsPer - 1) / rowsPer);
                if (page >= pages) page = pages - 1;
                if (count == 0) tx(g, f, "No payments in this time frame yet.", x0 + 6, hy + 16, MUTED);
                for (int i = 0; i < rowsPer; i++) {
                    int idx = page * rowsPer + i;
                    if (idx >= count) break;
                    Pay p = rows.get(idx);
                    int ry = hy + 14 + i * 14;
                    if (i % 2 == 0) g.fill(x0, ry - 2, x1, ry + 11, 0x14FFFFFF);
                    tx(g, f, fit(f, p.name(), mw * 40 / 100 - 10), x0 + 6, ry, WHITE);
                    int bc = p.in() ? GREEN : RED;
                    int bx = x0 + mw * 42 / 100;
                    rrect(g, bx, ry - 1, 38, 10, (bc & 0xFFFFFF) | 0x33000000);
                    small(g, f, p.in() ? "RECEIVED" : "SENT", bx + 4, ry + 1, bc, true);
                    String amt = p.in() ? "+" + money2(p.amount()) : "-" + money2(p.amount());
                    txb(g, f, amt, amtRight - twb(f, amt), ry, bc);
                    String when = ago(p.ts());
                    tx(g, f, when, x1 - 6 - tw(f, when), ry, MUTED);
                }
                small(g, f, "Page " + (page + 1) + " of " + pages + "  -  " + count + " shown", x0, py + ph - 17, MUTED, false);
            } else if (tab == 1) {
                // ---- players ----
                Map<String, double[]> agg = new LinkedHashMap<>();
                Map<String, String> disp = new HashMap<>();
                for (Pay p : v) {
                    String k = p.name().toLowerCase();
                    disp.putIfAbsent(k, p.name());
                    double[] a = agg.computeIfAbsent(k, kk -> new double[3]);
                    if (p.in()) a[0] += p.amount(); else a[1] += p.amount();
                    a[2]++;
                }
                List<Map.Entry<String, double[]>> rows = new ArrayList<>(agg.entrySet());
                rows.sort((r1, r2) -> Double.compare(r2.getValue()[0] - r2.getValue()[1], r1.getValue()[0] - r1.getValue()[1]));
                count = rows.size();

                int hy = py + 62;
                int cName = x0 + 6;
                int cIn = x0 + mw * 56 / 100, cOut = x0 + mw * 74 / 100, cNet = x0 + mw * 92 / 100, cN = x1 - 6;
                small(g, f, "PLAYER", cName, hy, MUTED, true);
                small(g, f, "PAID YOU", cIn - (int) (twb(f, "PAID YOU") * 0.7f), hy, MUTED, true);
                small(g, f, "YOU PAID", cOut - (int) (twb(f, "YOU PAID") * 0.7f), hy, MUTED, true);
                small(g, f, "NET", cNet - (int) (twb(f, "NET") * 0.7f), hy, MUTED, true);
                small(g, f, "#", cN - (int) (twb(f, "#") * 0.7f), hy, MUTED, true);
                g.fill(x0, hy + 8, x1, hy + 9, EDGE);

                int rp = Math.max(1, (ph - 100) / 14);
                int pages = Math.max(1, (count + rp - 1) / rp);
                if (page >= pages) page = pages - 1;
                if (count == 0) tx(g, f, "No players in this time frame yet.", x0 + 6, hy + 16, MUTED);
                for (int i = 0; i < rp; i++) {
                    int idx = page * rp + i;
                    if (idx >= count) break;
                    Map.Entry<String, double[]> e = rows.get(idx);
                    double[] a = e.getValue();
                    int ry = hy + 14 + i * 14;
                    if (i % 2 == 0) g.fill(x0, ry - 2, x1, ry + 11, 0x14FFFFFF);
                    tx(g, f, fit(f, disp.get(e.getKey()), cIn - x0 - 70), cName, ry, WHITE);
                    String s1 = money2(a[0]), s2 = a[1] == 0 ? "$0" : "-" + money2(a[1]);
                    double net = a[0] - a[1];
                    String s3 = signed(net), s4 = String.valueOf((int) a[2]);
                    txb(g, f, s1, cIn - twb(f, s1), ry, GREEN);
                    txb(g, f, s2, cOut - twb(f, s2), ry, a[1] == 0 ? MUTED : RED);
                    txb(g, f, s3, cNet - twb(f, s3), ry, net >= 0 ? GREEN : RED);
                    tx(g, f, s4, cN - tw(f, s4), ry, MUTED);
                }
                small(g, f, "Page " + (page + 1) + " of " + pages + "  -  " + count + " players", x0, py + ph - 17, MUTED, false);
            } else if (tab == 4) {
                // ---- dashboard ----
                double in = 0, out = 0;
                int nIn = 0, nOut = 0;
                Map<String, double[]> agg = new LinkedHashMap<>();
                Map<String, String> disp = new HashMap<>();
                for (Pay p : v) {
                    String k = p.name().toLowerCase();
                    disp.putIfAbsent(k, p.name());
                    double[] a = agg.computeIfAbsent(k, kk -> new double[1]);     // a[0] = their net: + they won, - they lost
                    if (p.in()) { in += p.amount(); nIn++; a[0] -= p.amount(); } else { out += p.amount(); nOut++; a[0] += p.amount(); }
                }
                count = v.size();
                int cw = (mw - 18) / 4, cy = py + 54, ch = 28;
                card(g, f, x0, cy, cw, ch, "RECEIVED", money2(in), nIn + " payments", GREEN);
                card(g, f, x0 + cw + 6, cy, cw, ch, "SENT", out == 0 ? "$0" : "-" + money2(out), nOut + " payments", RED);
                card(g, f, x0 + 2 * (cw + 6), cy, cw, ch, "NET PROFIT", signed(in - out), "received minus sent", in - out >= 0 ? GREEN : RED);
                card(g, f, x0 + 3 * (cw + 6), cy, cw, ch, "PAYMENTS", String.valueOf(count), count == 0 ? "none yet" : "avg " + money2((in + out) / count), TITLE);

                int gap = 6, my = py + 90, mh = 80;
                int lw = (mw - gap) * 45 / 100, rw = mw - gap - lw;
                int lx = x0, rx = x0 + lw + gap;

                // recent payments (left)
                rrect(g, lx, my, lw, mh, CARD);
                spaced(g, f, "RECENT", lx + 6, my + 5, WHITE);
                if (v.isEmpty()) tx(g, f, "No payments yet", lx + 6, my + 20, MUTED);
                for (int i = 0; i < 5 && i < v.size(); i++) {
                    Pay p = v.get(i);
                    int r = my + 19 + i * 11;
                    String amt = p.in() ? "+" + money2(p.amount()) : "-" + money2(p.amount());
                    String when = ago(p.ts());
                    int wx = lx + lw - 6 - (int) (tw(f, when) * 0.7f);
                    small(g, f, when, wx, r + 2, MUTED, false);
                    int ax = wx - 6 - twb(f, amt);
                    txb(g, f, amt, ax, r, p.in() ? GREEN : RED);
                    tx(g, f, fit(f, p.name(), ax - (lx + 6) - 6), lx + 6, r, WHITE);
                }

                // profit over time (right)
                rrect(g, rx, my, rw, mh, CARD);
                spaced(g, f, "PROFIT", rx + 6, my + 5, WHITE);
                double net = in - out;
                String nv = signed(net);
                txb(g, f, nv, rx + rw - 6 - twb(f, nv), my + 5, net >= 0 ? GREEN : RED);
                List<Double> pts = new ArrayList<>();
                double run = 0;
                for (int i = v.size() - 1; i >= 0; i--) {
                    Pay p = v.get(i);
                    run += p.in() ? p.amount() : -p.amount();
                    pts.add(run);
                }
                if (pts.size() < 2) tx(g, f, "Not enough payments yet", rx + 6, my + 36, MUTED);
                else chart(g, rx + 6, my + 20, rw - 12, mh - 26, pts);

                // top winners and losers (bottom)
                List<Map.Entry<String, double[]>> ups = new ArrayList<>();
                List<Map.Entry<String, double[]>> downs = new ArrayList<>();
                for (Map.Entry<String, double[]> e : agg.entrySet()) {
                    double pn = e.getValue()[0];
                    if (pn > 0) ups.add(e); else if (pn < 0) downs.add(e);
                }
                ups.sort((r1, r2) -> Double.compare(r2.getValue()[0], r1.getValue()[0]));
                downs.sort((r1, r2) -> Double.compare(r1.getValue()[0], r2.getValue()[0]));
                int colW = (mw - gap) / 2, ty = my + mh + 8, th = Math.max(30, Math.min(76, py + ph - 8 - ty));
                int rowsMax = Math.max(0, Math.min(5, (th - 19) / 11));
                int bx1 = x0 + colW + gap;
                rrect(g, x0, ty, colW, th, CARD);
                rrect(g, bx1, ty, colW, th, CARD);
                spaced(g, f, "TOP WINNERS", x0 + 6, ty + 5, GREEN);
                spaced(g, f, "TOP LOSERS", bx1 + 6, ty + 5, RED);
                if (ups.isEmpty()) tx(g, f, "None yet", x0 + 6, ty + 20, MUTED);
                if (downs.isEmpty()) tx(g, f, "None yet", bx1 + 6, ty + 20, MUTED);
                for (int i = 0; i < rowsMax; i++) {
                    int r = ty + 19 + i * 11;
                    if (i < ups.size()) {
                        String amt = money2(ups.get(i).getValue()[0]);
                        tx(g, f, fit(f, disp.get(ups.get(i).getKey()), colW - 18 - twb(f, amt)), x0 + 6, r, WHITE);
                        txb(g, f, amt, x0 + colW - 6 - twb(f, amt), r, GREEN);
                    }
                    if (i < downs.size()) {
                        String amt = money2(downs.get(i).getValue()[0]);
                        tx(g, f, fit(f, disp.get(downs.get(i).getKey()), colW - 18 - twb(f, amt)), bx1 + 6, r, WHITE);
                        txb(g, f, amt, bx1 + colW - 6 - twb(f, amt), r, RED);
                    }
                }
            } else if (tab == 5) {
                // ---- auctions ----
                long nowMs = System.currentTimeMillis();
                if (aucActive && aucCutOff && nowMs >= aucEnd) finishAuction();
                int mainW = (mw - 6) * 60 / 100, setW = mw - 6 - mainW;
                int sx = x0 + mainW + 6, sr = sx + setW;
                int top = py + 84, ph2 = 110;
                boolean readyState = !aucActive && !aucEnded;

                // recent rounds (click one to fill the setup)
                if (!aucActive) {
                    small(g, f, "RECENT ROUNDS", x0, py + 55, MUTED, true);
                    small(g, f, "click one to fill the setup", x0 + 62, py + 55, MUTED, false);
                    int chipW = Math.min(110, (mw - 12) / 3);
                    if (AUCS.isEmpty()) tx(g, f, "None yet", x0, py + 66, MUTED);
                    for (int i = 0; i < 3 && i < AUCS.size(); i++) {
                        Auc ch2 = AUCS.get(i);
                        int chx = x0 + i * (chipW + 6);
                        rrect(g, chx, py + 64, chipW, 14, CARD);
                        frame(g, chx, py + 64, chipW, 14, EDGE);
                        String lab = "x" + ch2.amount() + " " + ch2.item() + "  " + clock(AUC_TIMES[ch2.timeIdx()]);
                        tx(g, f, fit(f, lab, chipW - 10), chx + 5, py + 67, WHITE);
                    }
                }

                rrect(g, x0, top, mainW, ph2, CARD);
                rrect(g, sx, top, setW, ph2, CARD);
                g.fill(x0 + 2, top, x0 + mainW - 2, top + 1, aucActive || aucEnded ? PINK : PURPLE_EDGE);

                // status, countdown, item, top bid
                String st = aucActive ? "RUNNING" : (aucEnded ? "ENDED" : "READY");
                spaced(g, f, readyState ? "AUCTION" : "AUCTION  " + st, x0 + 8, top + 7, aucActive || aucEnded ? PINK : MUTED);
                long leftS = aucActive ? (Math.max(0, aucEnd - nowMs) + 999) / 1000 : (aucEnded ? 0 : AUC_TIMES[aucTimeIdx]);
                g.pose().pushMatrix();
                g.pose().translate(x0 + 8, top + 19);
                g.pose().scale(2.4f, 2.4f);
                txb(g, f, readyState ? "READY" : clock(leftS), 0, 0, PINK);
                g.pose().popMatrix();
                txb(g, f, fit(f, "x" + aucAmount + " " + aucItem, mainW - 16), x0 + 8, top + 48, WHITE);
                if (aucTop != null) {
                    String tb = money2(aucTopBid);
                    tx(g, f, fit(f, aucTop, mainW - 24 - twb(f, tb)), x0 + 8, top + 62, WHITE);
                    txb(g, f, tb, x0 + mainW - 8 - twb(f, tb), top + 62, GREEN);
                    small(g, f, aucBidders.size() + (aucBidders.size() == 1 ? " bidder" : " bidders"), x0 + 8, top + 74, MUTED, false);
                } else if (aucActive || aucEnded) {
                    txb(g, f, "No bids yet", x0 + 8, top + 62, PINK);
                    small(g, f, "0 bidders", x0 + 8, top + 74, MUTED, false);
                } else {
                    small(g, f, "Set the item and timer, then press START.", x0 + 8, top + 64, MUTED, false);
                }
                small(g, f, aucMinIdx == 0 ? "Any payment you receive counts as a bid." : "Payments under " + money(AUC_MINS[aucMinIdx]) + " do not count.", x0 + 8, top + 90, MUTED, false);

                if (readyState) {
                    // setup
                    small(g, f, "ITEM", sx + 8, top + 7, MUTED, true);
                    tx(g, f, fit(f, aucItem, setW - 16), sx + 8, top + 14, WHITE);
                    small(g, f, "change with /paydash item <name>", sx + 8, top + 24, MUTED, false);
                    String[] sl = {"AMOUNT", "TIMER", "MIN BID"};
                    String[] sv = {String.valueOf(aucAmount), clock(AUC_TIMES[aucTimeIdx]), aucMinIdx == 0 ? "None" : money(AUC_MINS[aucMinIdx])};
                    for (int i = 0; i < 3; i++) {
                        int ry = top + 33 + i * 15;
                        small(g, f, sl[i], sx + 8, ry + 3, MUTED, true);
                        txb(g, f, sv[i], sr - 35 - twb(f, sv[i]) / 2, ry + 1, WHITE);
                    }
                    small(g, f, "CUT OFF AT ZERO", sx + 8, top + 82, MUTED, true);
                } else {
                    // control for the running or finished auction
                    small(g, f, "CONTROL", sx + 8, top + 7, MUTED, true);
                    small(g, f, "Control the running event here too.", sx + 8, top + 17, MUTED, false);
                    int rcx = sx + setW / 2, rcy = top + 56;
                    ring(g, rcx, rcy, 22, aucActive ? Math.max(0, Math.min(1.0, (aucEnd - nowMs) / (AUC_TIMES[aucTimeIdx] * 1000.0))) : 0);
                    String rt = clock(leftS);
                    txb(g, f, rt, rcx - twb(f, rt) / 2, rcy - 4, WHITE);
                }

                // history (a bar you can open and close)
                int hy = top + ph2 + 8;
                rrect(g, x0, hy - 4, mw, 16, CARD);
                spaced(g, f, "HISTORY", x0 + 8, hy, WHITE);
                small(g, f, AUCS.size() + (AUCS.size() == 1 ? " finished event" : " finished events"), x0 + 64, hy + 2, MUTED, false);
                int hrows = histOpen ? Math.max(0, Math.min(5, Math.min(AUCS.size(), (py + ph - 8 - (hy + 20)) / 12))) : 0;
                if (histOpen && AUCS.isEmpty()) tx(g, f, "No finished auctions yet.", x0 + 6, hy + 20, MUTED);
                for (int i = 0; i < hrows; i++) {
                    Auc au = AUCS.get(i);
                    int ry = hy + 20 + i * 12;
                    if (i % 2 == 0) g.fill(x0, ry - 2, x1, ry + 10, 0x14FFFFFF);
                    tx(g, f, fit(f, "x" + au.amount() + " " + au.item(), mw * 34 / 100 - 8), x0 + 6, ry, WHITE);
                    tx(g, f, fit(f, au.winner(), mw * 26 / 100), x0 + mw * 38 / 100, ry, MUTED);
                    String bs = au.bid() > 0 ? money2(au.bid()) : "no bids";
                    txb(g, f, bs, x0 + mw * 82 / 100 - twb(f, bs), ry, au.bid() > 0 ? GREEN : MUTED);
                    String when = ago(au.ts());
                    tx(g, f, when, x1 - 6 - tw(f, when), ry, MUTED);
                }
            } else if (tab == 7) {
                // ---- streamers ----
                int cx0 = x0 + 76, cwt = mw - 76, rowsTop = py + 72;
                for (int k = 0; k < SUB_NAMES.length; k++) {
                    int sy2 = py + 54 + k * 19;
                    boolean sel = stSub == k;
                    rrect(g, x0, sy2, 70, 16, sel ? SUB_COLORS[k] : alpha(SUB_COLORS[k], 0.5));
                    if (sel) frame(g, x0, sy2, 70, 16, 0xFFFFFFFF);
                    tx(g, f, SUB_NAMES[k], x0 + 6, sy2 + 4, WHITE);
                }
                tx(g, f, SUB_TITLES[stSub], cx0, py + 54, MUTED);
                g.fill(cx0, py + 66, cx0 + cwt, py + 67, EDGE);
                List<Row> rs = SUBS.get(stSub);
                int[][] lay = layout(rs, cx0, rowsTop, cwt);
                for (int i = 0; i < rs.size(); i++) {
                    Row r = rs.get(i);
                    int[] bb = lay[i];
                    if (r.type() == 4) continue;
                    rrect(g, bb[0], bb[1], bb[2], bb[3], CARD);
                    if (r.type() == 2) {
                        String pv = r.val().get();
                        txb(g, f, pv, bb[0] + bb[2] / 2 - twb(f, pv) / 2, bb[1] + 4, WHITE);
                    } else {
                        tx(g, f, r.label(), bb[0] + 6, bb[1] + 4, WHITE);
                        if (r.type() == 1) {
                            String sv2 = r.val().get();
                            txb(g, f, sv2, bb[0] + bb[2] - 36 - twb(f, sv2) / 2, bb[1] + 4, 0xFFFFC857);
                        } else if (r.type() == 3) {
                            int sx2 = bb[0] + bb[2] - 6 - 18;
                            g.fill(sx2 - 1, bb[1] + 1, sx2 + 19, bb[1] + 15, EDGE);
                            g.fill(sx2, bb[1] + 2, sx2 + 18, bb[1] + 14, r.color().getAsInt());
                        }
                    }
                }
                int ny = lay[lay.length - 1][1] + 22;
                switch (stSub) {
                    case 0 -> {
                        small(g, f, "Preview", cx0, ny - 4, MUTED, false);
                        chatPreview(g, f, cx0, ny + 6);
                    }
                    case 1 -> {
                        small(g, f, hideOn ? "Filter is on: payments under " + money(HIDE_MINS[hideMinIdx]) + " are hidden." : "Filter is off: all payments are shown.", cx0, ny, WHITE, false);
                        small(g, f, "Hidden payments still count for the profit and the popup.", cx0, ny + 10, MUTED, false);
                    }
                    case 3 -> small(g, f, "/win adds one and /lose resets it. Click a color square to change it.", cx0, ny, MUTED, false);
                    case 6 -> {
                        String shown = String.join(" | ", java.util.Arrays.stream(OV_LINES).filter(q -> !q.isEmpty()).toList());
                        small(g, f, fit(f, "Set the lines in chat: /paydash line 1 <text>.   Now: " + (shown.isEmpty() ? "no lines yet" : shown), (int) (cwt / 0.7f)), cx0, ny, MUTED, false);
                    }
                    case 7 -> small(g, f, "Plays when a popup shows or a confirm screen opens. Try the Preview buttons.", cx0, ny, MUTED, false);
                    case 8 -> {
                        small(g, f, "It never sends anything. After CONFIRM, press T, Up, then Enter", cx0, ny, MUTED, false);
                        small(g, f, "to send the payment yourself. The test closes this menu.", cx0, ny + 10, MUTED, false);
                    }
                    case 2, 4, 5 -> small(g, f, "Click a color square to step through the colors.", cx0, ny, MUTED, false);
                    default -> { }
                }
            } else if (tab == 6) {
                // ---- themes ----
                int gCell = (mw - 8) / 3;
                for (int i = 0; i < THEME_NAMES.length; i++) {
                    int gx = x0 + (i % 3) * (gCell + 4), gy = py + 54 + (i / 3) * 21;
                    boolean sel = theme == i;
                    rrect(g, gx, gy, gCell, 18, sel ? PURPLE : CARD);
                    if (!sel) frame(g, gx, gy, gCell, 18, EDGE);
                    g.fillGradient(gx + 5, gy + 4, gx + 23, gy + 14, THEME_COL[i][0], THEME_COL[i][1]);
                    if (sel) txb(g, f, THEME_NAMES[i], gx + 29, gy + 5, WHITE); else tx(g, f, THEME_NAMES[i], gx + 29, gy + 5, WHITE);
                }
            } else if (tab == 3) {
                // ---- rakeback (read from the balance HUD mod) ----
                readHud();
                double min = hudMin > 0 ? hudMin : 1e9, rate = hudRate / 100.0;
                List<Map.Entry<String, Double>> rows = new ArrayList<>(HUD_ROWS);
                rows.sort((r1, r2) -> Double.compare(r2.getValue(), r1.getValue()));
                double owe = 0;
                int ready = 0;
                for (Map.Entry<String, Double> e : rows) {
                    if (e.getValue() >= min) { ready++; owe += e.getValue() * rate; }
                }
                count = rows.size();

                int cw = (mw - 12) / 3, cy = py + 54, ch = 28;
                card(g, f, x0, cy, cw, ch, "PLAYERS", String.valueOf(count), "who are down to you", TITLE);
                card(g, f, x0 + cw + 6, cy, cw, ch, "READY", String.valueOf(ready), "at or over " + money(min), PINK);
                card(g, f, x0 + 2 * (cw + 6), cy, cw, ch, "YOU OWE", owe == 0 ? "$0" : money2(owe), trim(hudRate) + "% of their loss", TITLE);

                int hy = py + 92;
                small(g, f, "#", x0 + 8, hy, MUTED, true);
                small(g, f, "PLAYER", x0 + 24, hy, MUTED, true);
                small(g, f, "LOSS / MINIMUM", x1 - 6 - (int) (twb(f, "LOSS / MINIMUM") * 0.7f), hy, MUTED, true);
                g.fill(x0, hy + 8, x1, hy + 9, EDGE);

                int rp = Math.max(1, (ph - 130) / 18);
                int pages = Math.max(1, (count + rp - 1) / rp);
                if (page >= pages) page = pages - 1;
                if (!hudFound) tx(g, f, "Waiting for the balance HUD mod. It needs its latest update.", x0 + 6, hy + 16, MUTED);
                else if (count == 0) tx(g, f, "Nobody is down to you yet.", x0 + 6, hy + 16, MUTED);
                for (int i = 0; i < rp; i++) {
                    int idx = page * rp + i;
                    if (idx >= count) break;
                    Map.Entry<String, Double> e = rows.get(idx);
                    double loss = e.getValue();
                    int ry = hy + 14 + i * 18;
                    rrect(g, x0, ry, mw, 16, CARD);
                    small(g, f, String.valueOf(idx + 1), x0 + 8, ry + 5, MUTED, false);
                    String right = money(loss) + " / " + money(min);
                    txb(g, f, right, x1 - 6 - twb(f, right), ry + 1, loss >= min ? PINK : WHITE);
                    tx(g, f, fit(f, e.getKey(), mw - 40 - twb(f, right)), x0 + 24, ry + 1, WHITE);
                    int bx0 = x0 + 24, bx1 = x1 - 6;
                    g.fill(bx0, ry + 13, bx1, ry + 15, 0x28FFFFFF);
                    g.fill(bx0, ry + 13, bx0 + (int) ((bx1 - bx0) * Math.min(1.0, loss / min)), ry + 15, PINK);
                }
                small(g, f, "Page " + (page + 1) + " of " + pages + "  -  " + (hudFound ? "from the balance HUD mod, updated " + ago(hudAt) : "no data yet"), x0, py + ph - 17, MUTED, false);
            } else {
                // ---- settings ----
                String[] tt = {"PROFIT TRACKER", "RAKEBACK", "BALANCE"};
                String[] dd = {"The profit panel with the chart, top winners and losers, and recent bets", "Who is close to rakeback and how much you owe them", "Your balance box"};
                int[] hs = {1, 2, 0};
                for (int i = 0; i < 3; i++) {
                    int cy2 = py + 60 + i * 40;
                    rrect(g, x0, cy2, mw, 34, CARD);
                    g.fill(x0, cy2 + 2, x0 + 2, cy2 + 32, PURPLE);
                    spaced(g, f, tt[i], x0 + 10, cy2 + 6, WHITE);
                    small(g, f, dd[i], x0 + 10, cy2 + 20, MUTED, false);
                    String stx = hudState(hs[i]) ? "ON" : "OFF";
                    small(g, f, stx, x1 - 8 - 36 - 6 - (int) (twb(f, stx) * 0.7f), cy2 + 14, hudState(hs[i]) ? WHITE : MUTED, true);
                }
                small(g, f, "These switches control your Balance HUD mod, so it needs its latest update installed too.", x0, py + 182, MUTED, false);
            }

            // our own look for the buttons (the real, invisible buttons still receive the clicks)
            for (Btn b : btns) {
                if (!b.visible().getAsBoolean()) continue;
                Button w = b.w();
                int bx = b.vx(), by = b.vy(), bw = b.vw(), bh = b.vh();
                boolean act = b.active().getAsBoolean(), hov = w.isHovered();
                if (b.label().equals("#chip") || b.label().equals("#theme") || b.label().equals("#sub") || b.label().equals("#swatch")) continue;
                if (b.toggle()) {
                    pill(g, bx, by, bw, bh, act ? PURPLE : 0xFF241C3E);
                    int kr = bh / 2 - 2;
                    int kcx = act ? bx + bw - bh / 2 : bx + bh / 2;
                    disc(g, kcx, by + bh / 2, kr, act ? 0xFF14102A : MUTED);
                    continue;
                }
                boolean danger = b.label().equals("RESET SESSION");
                rrect(g, bx, by, bw, bh, act ? PURPLE : (hov ? 0xFF1F1740 : CARD));
                frame(g, bx, by, bw, bh, danger ? RED : (act ? PURPLE_EDGE : (hov ? 0xFF6B54B5 : EDGE)));
                int col = danger ? RED : (act ? WHITE : (hov ? WHITE : MUTED));
                int lw = (int) (twb(f, b.label()) * 0.7f);
                small(g, f, b.label(), bx + (bw - lw) / 2, by + (bh - 6) / 2, col, true);
            }
        }

        void card(GuiGraphicsExtractor g, Font f, int x, int y, int w, int h, String label, String value, String sub, int accent) {
            rrect(g, x, y, w, h, CARD);
            g.fill(x + 2, y, x + w - 2, y + 1, accent);
            small(g, f, label, x + 5, y + 5, MUTED, true);
            txb(g, f, fit(f, value, w - 8), x + 5, y + 12, accent == TITLE ? WHITE : accent);
            small(g, f, sub, x + 5, y + 23, MUTED, false);
        }
    }
}
