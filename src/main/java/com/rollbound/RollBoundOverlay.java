package com.rollbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.runelite.api.Client;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Renders and animates the RollBound rolling strip overlay.
 *
 * IMPORTANT: This version avoids any last-second "item swap".
 * The final (winning) item is chosen up-front and the reel is prebuilt so the
 * scroll animation naturally lands on the winner.
 */
@Singleton
public class RollBoundOverlay extends Overlay
{
    private static final Logger log = LoggerFactory.getLogger(RollBoundOverlay.class);
    // Timing / motion
    private static final long HIGHLIGHT_DURATION_MS = 3000L;
    private static final long CHOICE_TRANSITION_MS = 420L; // roll end -> expand/select
    private static final long CHOICE_EXIT_MS = 520L;       // click -> fade out
    private static final long CHOICE_HOLD_AFTER_PICK_MS = 2000L; // keep chosen item visible longer
    private static final float MAX_DT = 0.05f;  // clamp large frame gaps

    // Layout
    private static final int ICON_COUNT = 5;
    private static final int DRAW_COUNT = ICON_COUNT + 1;
    private static final int ICON_W = 32;
    private static final int ICON_H = 32;
    private static final int SPACING = 5;
    private static final float STEP = ICON_W + SPACING;

    // RollBound vertical reel view
    private static final int VISIBLE_ROWS = 3; // show 3 items per column while rolling
    private static final float VSTEP = ICON_H + SPACING;

    // Reel settings
    private static final int REEL_LEN = 60; // how many total items to scroll through
    // Extra padding so there are always icons visible after the winning item.
    private static final int REEL_TAIL_PAD = DRAW_COUNT;

    // Box / visual tweaks
    private static final int EXTRA_WIDTH_BUFFER = 16;
    private static final int OUTER_PAD = 5;
    private static final int OFFSET_TOP = 20;
    private static final int BOX_SHIFT_X = -26;
    private static final int CENTER_NUDGE_PX = 17;
    private static final int FRAME_CONTENT_INSET = 4;
    private static final Color SHADE_BOTTOM = new Color(0, 0, 0, 60);
    private static final Color SHADE_TOP = new Color(255, 255, 255, 25);

    private final Client client;
    private final ItemManager itemManager;

    /** Prebuilt reel for the current (single) roll. */
    private final List<Integer> reel = Collections.synchronizedList(new ArrayList<>());

    /** RollBound selection: multiple duplicated reels (same animation duplicated). */
    private static final class ReelState
    {
        final List<Integer> reel = new ArrayList<>();
        float scrollPx = 0f;
        float maxScrollPx = 0f;
        int finalItemId = 0;
        Rectangle clickableCenterRect = null;
    }

    private final List<ReelState> choiceReels = new ArrayList<>();
    private volatile boolean choiceIsRoller = false;
    private volatile boolean choiceAllowSelect = false;
    private volatile Integer chosenItemId = null;
    private volatile long chosenAtNs = 0L;
    private volatile java.util.function.IntConsumer onChoiceSelected = null;

    // A Firebase pick can arrive before its queued animation becomes active. Keep it until that event starts.
    private final ConcurrentMap<String, Integer> pendingRemoteChoices = new ConcurrentHashMap<>();
    private static final int MAX_PENDING_REMOTE_CHOICES = 100;

    // Hover state for selection tiles (roller only)
    private volatile Integer hoveredItemId = null;
    private volatile long hoverChangedNs = 0L;

    // Roll overlay minimise state. The preference persists between rolls while the plugin is enabled.
    // When a new roll starts while minimised, the compact control pulses until it is reopened.
    private volatile boolean rollOverlayMinimised = false;
    private volatile boolean minimisedHasUnseenRoll = false;
    private volatile long minimiseAttentionStartedNs = 0L;
    private volatile Rectangle minimiseButtonRect = null;
    private static final int MINIMISE_BUTTON_W = 64;
    private static final int MINIMISE_BUTTON_H = 24;

    private static float toDb(int percent)
    {
        int p = Math.max(0, Math.min(100, percent));
        if (p == 0)
        {
            return -80.0f; // effectively mute
        }
        double lin = p / 100.0;
        return (float) (20.0 * Math.log10(lin));
    }

    // Textures
    private final BufferedImage rollBoxImage =
            ImageUtil.loadImageResource(getClass(), "/com/rollbound/roll_box.png");
    private final BufferedImage iconFrameImage =
            ImageUtil.loadImageResource(getClass(), "/com/rollbound/icon_slot.png");

    // NEW: selection tile frame (non-square)
    private final BufferedImage selectionFrameImage =
            ImageUtil.loadImageResource(getClass(), "/com/rollbound/new-frame.png");

    @Inject
    private AudioPlayer audioPlayer;

    @Inject
    private RollBoundConfig config;

    // Animation state
    private volatile boolean isAnimating = false;
    private volatile long rollDurationMs;
    private volatile long rollStartNs = 0L;

    // Motion state
    private float scrollPx = 0f;
    private float maxScrollPx = 0f;
    private volatile long lastUpdateNanos = 0L;

    // Winner
    private volatile int finalItemId = 0;

    // RollBound selection metadata
    private volatile String choiceEventId = null;

    private volatile String choiceRollerName = "";
    @Inject
    public RollBoundOverlay(Client client, ItemManager itemManager)
    {
        this.client = client;
        this.itemManager = itemManager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }


    /**
     * Immediately clears all roll/choice presentation state.
     * Safe to call on logout, account switches, manual UI reset, or an interrupted roll.
     */
    public void resetUiState()
    {
        isAnimating = false;
        rollStartNs = 0L;
        lastUpdateNanos = 0L;
        scrollPx = 0f;
        maxScrollPx = 0f;
        finalItemId = 0;

        choiceEventId = null;
        choiceRollerName = "";
        choiceIsRoller = false;
        choiceAllowSelect = false;
        chosenItemId = null;
        chosenAtNs = 0L;
        onChoiceSelected = null;
        hoveredItemId = null;
        hoverChangedNs = 0L;
        minimisedHasUnseenRoll = false;
        minimiseAttentionStartedNs = 0L;
        minimiseButtonRect = null;
        pendingRemoteChoices.clear();

        synchronized (choiceReels)
        {
            for (ReelState rs : choiceReels)
            {
                rs.clickableCenterRect = null;
            }
            choiceReels.clear();
        }
        synchronized (reel)
        {
            reel.clear();
        }
    }

    /**
     * Starts the roll animation.
     *
     * @param finalItemId the item to land on (0 = let supplier decide)
     * @param rollDurationMs spin duration (ms) before highlight
     * @param randomLockedItemSupplier supplier of random locked item ids
     */
    public void startRollAnimation(int finalItemId, int rollDurationMs, Supplier<Integer> randomLockedItemSupplier)
    {
        // Reset RollBound selection state
        synchronized (choiceReels)
        {
            choiceReels.clear();
        }
        choiceAllowSelect = false;
        choiceIsRoller = false;
        chosenItemId = null;
        choiceEventId = null;

        if (config.enableRollSounds())
        {
            Thread t = new Thread(() ->
            {
                try
                {
                    float volumeDb = toDb(config.rollSoundVolume());
                    audioPlayer.play(RollBoundOverlay.class, "/com/rollbound/tick.wav", volumeDb);
                }
                catch (IOException ex)
                {
                    log.warn("RollBound: failed to play tick.wav", ex);
                }
                catch (Exception ex)
                {
                    log.warn("RollBound: unexpected error while playing tick.wav", ex);
                }
            }, "RollBound-Audio");
            t.setDaemon(true);
            t.start();
        }

        this.rollDurationMs = rollDurationMs;
        this.rollStartNs = System.nanoTime();
        this.scrollPx = 0f;
        this.isAnimating = true;
        markNewRollWhileMinimised();
        this.lastUpdateNanos = System.nanoTime();

        final int resolvedFinal = (finalItemId > 0) ? finalItemId : safeGet(randomLockedItemSupplier);
        this.finalItemId = resolvedFinal;

        // Preload winner icon so there's no late image load / pop-in.
        try { itemManager.getImage(resolvedFinal, 1, false); } catch (Exception ignored) {}

        synchronized (reel)
        {
            reel.clear();
            for (int i = 0; i < REEL_LEN - 1; i++)
            {
                reel.add(safeGet(randomLockedItemSupplier));
            }
            // Place the winner, then pad extra items so the reel still shows icons to the right
            // once the winner is centred.
            reel.add(resolvedFinal);
            final int winnerIndex = reel.size() - 1;

            for (int i = 0; i < REEL_TAIL_PAD; i++)
            {
                reel.add(safeGet(randomLockedItemSupplier));
            }

            // Align end-of-roll so the winner ends up centred.
            final int centerIndex = ICON_COUNT / 2;
            final int endStartIndex = Math.max(0, winnerIndex - centerIndex);
            this.maxScrollPx = endStartIndex * STEP;
        }
    }

    /**
     * Starts a RollBound selection animation: duplicates the original roll strip up to 5 times.
     * After the roll ends, the centre icon for each strip scales up and becomes clickable
     * (only for the roller).
     */
    public void startChoiceAnimation(String eventId,
                                     List<Integer> finalItemIds,
                                     int rollDurationMs,
                                     Supplier<Integer> randomLockedItemSupplier,
                                     boolean isRoller,
                                     java.util.function.IntConsumer onSelected,
                                     String rollerName)
    {
        // Single tick at start (same as original)
        if (config.enableRollSounds())
        {
            Thread t = new Thread(() ->
            {
                try
                {
                    float volumeDb = toDb(config.rollSoundVolume());
                    audioPlayer.play(RollBoundOverlay.class, "/com/rollbound/tick.wav", volumeDb);
                }
                catch (IOException ex)
                {
                    log.warn("RollBound: failed to play tick.wav", ex);
                }
                catch (Exception ex)
                {
                    log.warn("RollBound: unexpected error while playing tick.wav", ex);
                }
            }, "RollBound-Audio");
            t.setDaemon(true);
            t.start();
        }

        this.rollDurationMs = rollDurationMs;
        this.rollStartNs = System.nanoTime();
        this.isAnimating = true;
        this.lastUpdateNanos = System.nanoTime();
        markNewRollWhileMinimised();

        this.choiceEventId = eventId;
        this.choiceRollerName = (rollerName == null) ? "" : rollerName;
        this.choiceIsRoller = isRoller;
        this.choiceAllowSelect = false;
        this.onChoiceSelected = onSelected;
        this.chosenItemId = null;
        this.chosenAtNs = 0L;

        final List<Integer> finals = (finalItemIds == null) ? Collections.emptyList() : finalItemIds;

        synchronized (choiceReels)
        {
            choiceReels.clear();
            int count = Math.max(1, Math.min(5, finals.size()));
            for (int i = 0; i < count; i++)
            {
                final int resolvedFinal = finals.get(i);
                ReelState rs = new ReelState();
                rs.finalItemId = resolvedFinal;
                // Preload
                try { itemManager.getImage(resolvedFinal, 1, false); } catch (Exception ignored) {}

                rs.reel.clear();
                for (int j = 0; j < REEL_LEN - 1; j++)
                {
                    rs.reel.add(safeGet(randomLockedItemSupplier));
                }
                rs.reel.add(resolvedFinal);
                final int winnerIndex = rs.reel.size() - 1;
                for (int j = 0; j < REEL_TAIL_PAD; j++)
                {
                    rs.reel.add(safeGet(randomLockedItemSupplier));
                }

                // IMPORTANT (RollBound vertical reels): ensure the *centre visible row* lands on the final item.
                // The selection UI uses rs.finalItemId, so the animation must end with that item in the middle.
                final int centerRow = VISIBLE_ROWS / 2; // for 3 rows, this is 1
                final int endStartIndex = Math.max(0, winnerIndex - centerRow);
                rs.maxScrollPx = endStartIndex * VSTEP;
                rs.scrollPx = 0f;
                choiceReels.add(rs);
            }
        }
    }

    /** Called when a remote pick arrives (non-roller clients). */
    public void applyRemoteChoiceSelection(String eventId, int chosenItemId)
    {
        if (eventId == null || eventId.isEmpty() || chosenItemId <= 0)
        {
            return;
        }

        if (eventId.equals(choiceEventId) && choiceAllowSelect)
        {
            this.chosenItemId = chosenItemId;
            this.chosenAtNs = System.nanoTime();
            return;
        }

        pendingRemoteChoices.put(eventId, chosenItemId);
        if (pendingRemoteChoices.size() > MAX_PENDING_REMOTE_CHOICES)
        {
            String oldestArbitrary = pendingRemoteChoices.keySet().stream().findFirst().orElse(null);
            if (oldestArbitrary != null)
            {
                pendingRemoteChoices.remove(oldestArbitrary);
            }
        }
    }

    /** Applies a pick that arrived before this event reached the front of the local animation queue. */
    public void applyPendingRemoteChoiceSelection(String eventId)
    {
        if (eventId == null || !eventId.equals(choiceEventId))
        {
            return;
        }
        Integer pending = pendingRemoteChoices.remove(eventId);
        if (pending != null && pending > 0 && chosenItemId == null)
        {
            chosenItemId = pending;
            chosenAtNs = System.nanoTime();
        }
    }

    /** True only when this client is the authoritative roller and still needs to make the pick. */
    public boolean isAwaitingLocalChoice()
    {
        return isAnimating && choiceEventId != null && choiceIsRoller && chosenItemId == null;
    }

    /** Whether the overlay is currently in the RollBound selection phase and should block clicks. */
    
    /** Returns true if the current RollBound roll event is still awaiting a selection (rolling or selecting). */
    public boolean isChoicePending(String eventId)
    {
        if (!isAnimating) return false;
        if (choiceEventId == null) return false;
        if (eventId != null && !eventId.equals(choiceEventId)) return false;
        return chosenItemId == null;
    }

    /** Returns the chosen item id for the current RollBound roll event, or null if not chosen yet. */
    public Integer getChosenItemId(String eventId)
    {
        if (choiceEventId == null) return null;
        if (eventId != null && !eventId.equals(choiceEventId)) return null;
        return chosenItemId;
    }
public boolean isBlockingClicks()
    {
        if (!isAnimating) return false;
        if (choiceEventId == null) return false;
        // Only block clicks while waiting for a pick.
        return choiceAllowSelect && chosenItemId == null;
    }

    /** Handle a click (canvas coordinates). Returns true if consumed. */
    public boolean handleClick(Point canvasPoint)
    {
        if (canvasPoint == null) return false;

        // The minimise/restore control is available to every client, not only the roller.
        Rectangle button = minimiseButtonRect;
        if (button != null && button.contains(canvasPoint))
        {
            if (rollOverlayMinimised)
            {
                // If the roll resolved while hidden, replay just enough of the result phase
                // for the user to see what happened when they restore the overlay.
                if (minimisedHasUnseenRoll)
                {
                    long nowNs = System.nanoTime();
                    if (choiceEventId != null && chosenItemId != null)
                    {
                        chosenAtNs = nowNs;
                    }
                    else if (choiceEventId == null)
                    {
                        long elapsedMs = rollStartNs > 0 ? (nowNs - rollStartNs) / 1_000_000L : 0L;
                        if (elapsedMs > rollDurationMs + HIGHLIGHT_DURATION_MS)
                        {
                            rollStartNs = nowNs - (Math.max(0L, rollDurationMs) * 1_000_000L);
                        }
                    }
                }
                rollOverlayMinimised = false;
                minimisedHasUnseenRoll = false;
                minimiseAttentionStartedNs = 0L;
            }
            else
            {
                rollOverlayMinimised = true;
                minimisedHasUnseenRoll = false;
                minimiseAttentionStartedNs = 0L;
                hoveredItemId = null;
            }
            return true;
        }

        if (!choiceIsRoller) return false;
        if (!isBlockingClicks()) return false;

        synchronized (choiceReels)
        {
            for (ReelState rs : choiceReels)
            {
                if (rs.clickableCenterRect != null && rs.clickableCenterRect.contains(canvasPoint))
                {
                    // Choose this reel's final item
                    int picked = rs.finalItemId;
                    chosenItemId = picked;
                    chosenAtNs = System.nanoTime();

                    if (onChoiceSelected != null)
                    {
                        try
                        {
                            onChoiceSelected.accept(picked);
                        }
                        catch (Exception error)
                        {
                            log.warn("RollBound: choice selection callback failed for event {}", choiceEventId, error);
                        }
                    }
                    return true;
                }
            }
        }
        return false; // allow click-through except when a tile is clicked
    }

    /** Track hover for selection tiles (roller only). */
    public void handleMouseMove(Point canvasPoint)
    {
        if (!isAnimating || choiceEventId == null)
        {
            hoveredItemId = null;
            return;
        }
        if (!(choiceAllowSelect && chosenItemId == null) || !choiceIsRoller)
        {
            hoveredItemId = null;
            return;
        }
        if (canvasPoint == null)
        {
            hoveredItemId = null;
            return;
        }

        Integer hover = null;
        synchronized (choiceReels)
        {
            for (ReelState rs : choiceReels)
            {
                if (rs.clickableCenterRect != null && rs.clickableCenterRect.contains(canvasPoint))
                {
                    hover = rs.finalItemId;
                    break;
                }
            }
        }

        if ((hoveredItemId == null && hover != null) || (hoveredItemId != null && !hoveredItemId.equals(hover)))
        {
            hoveredItemId = hover;
            hoverChangedNs = System.nanoTime();
        }
        else
        {
            hoveredItemId = hover;
        }
    }

    private void markNewRollWhileMinimised()
    {
        if (rollOverlayMinimised)
        {
            minimisedHasUnseenRoll = true;
            minimiseAttentionStartedNs = System.nanoTime();
        }
    }

    /** Draws the compact minimise/restore control. */
    private void drawMinimiseButton(Graphics2D g, boolean minimised)
    {
        final int vpX = client.getViewportXOffset();
        final int vpY = client.getViewportYOffset();
        final int vpW = client.getViewportWidth();
        final int centerX = vpX + (vpW / 2);

        // Keep the control near the roll UI when expanded and centred when minimised.
        final int x = minimised
                ? centerX - (MINIMISE_BUTTON_W / 2)
                : Math.min(vpX + vpW - MINIMISE_BUTTON_W - 8, centerX + 200);
        final int y = vpY + 4;
        final Rectangle rect = new Rectangle(x, y, MINIMISE_BUTTON_W, MINIMISE_BUTTON_H);
        minimiseButtonRect = rect;

        final Composite oldComposite = g.getComposite();
        final Stroke oldStroke = g.getStroke();
        final Font oldFont = g.getFont();

        float pulse = 0f;
        if (minimised && minimisedHasUnseenRoll)
        {
            long started = minimiseAttentionStartedNs > 0 ? minimiseAttentionStartedNs : System.nanoTime();
            double secs = (System.nanoTime() - started) / 1_000_000_000.0;
            pulse = (float) (0.5 + 0.5 * Math.sin(secs * 5.5));

            int glowPad = 5 + Math.round(3f * pulse);
            g.setComposite(AlphaComposite.SrcOver.derive(0.22f + 0.28f * pulse));
            g.setColor(new Color(255, 196, 64));
            g.setStroke(new BasicStroke(3f));
            g.drawRoundRect(x - glowPad, y - glowPad,
                    MINIMISE_BUTTON_W + glowPad * 2, MINIMISE_BUTTON_H + glowPad * 2, 14, 14);
        }

        g.setComposite(AlphaComposite.SrcOver.derive(0.88f));
        g.setColor(new Color(24, 24, 24));
        g.fillRoundRect(x, y, MINIMISE_BUTTON_W, MINIMISE_BUTTON_H, 10, 10);

        g.setComposite(AlphaComposite.SrcOver);
        g.setStroke(new BasicStroke(minimised && minimisedHasUnseenRoll ? 2f : 1f));
        g.setColor(minimised && minimisedHasUnseenRoll
                ? new Color(255, 210, 90)
                : new Color(190, 190, 190));
        g.drawRoundRect(x, y, MINIMISE_BUTTON_W, MINIMISE_BUTTON_H, 10, 10);

        Font buttonFont = oldFont.deriveFont(Font.BOLD, 12f);
        try
        {
            buttonFont = net.runelite.client.ui.FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 12f);
        }
        catch (Throwable ignored) {}
        g.setFont(buttonFont);
        String label = minimised ? "ROLLS +" : "ROLLS -";
        FontMetrics fm = g.getFontMetrics();
        int tx = x + (MINIMISE_BUTTON_W - fm.stringWidth(label)) / 2;
        int ty = y + (MINIMISE_BUTTON_H - fm.getHeight()) / 2 + fm.getAscent();
        g.setColor(Color.BLACK);
        g.drawString(label, tx + 1, ty + 1);
        g.setColor(minimised && minimisedHasUnseenRoll ? new Color(255, 230, 145) : Color.WHITE);
        g.drawString(label, tx, ty);

        g.setFont(oldFont);
        g.setStroke(oldStroke);
        g.setComposite(oldComposite);
    }

    /**
     * Keeps roll timing and remote-choice state moving while the full roll UI is hidden.
     */
    private Dimension renderMinimised(Graphics2D g)
    {
        final long nowNs = System.nanoTime();
        final long elapsedMs = (nowNs - rollStartNs) / 1_000_000L;

        if (choiceEventId != null)
        {
            final long transElapsed = Math.max(0L, elapsedMs - rollDurationMs);
            final float transT = Math.min(1f, transElapsed / (float) CHOICE_TRANSITION_MS);
            choiceAllowSelect = (transT >= 0.999f);

            if (chosenItemId != null && chosenAtNs > 0)
            {
                long sincePickMs = (nowNs - chosenAtNs) / 1_000_000L;
                if (sincePickMs > (CHOICE_HOLD_AFTER_PICK_MS + CHOICE_EXIT_MS) && !minimisedHasUnseenRoll)
                {
                    isAnimating = false;
                    minimiseButtonRect = null;
                    return null;
                }
            }
        }
        else if (elapsedMs > rollDurationMs + HIGHLIGHT_DURATION_MS && !minimisedHasUnseenRoll)
        {
            isAnimating = false;
            minimiseButtonRect = null;
            return null;
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawMinimiseButton(g, true);
        return null;
    }

    private static int safeGet(Supplier<Integer> supplier)
    {
        try
        {
            if (supplier != null)
            {
                Integer v = supplier.get();
                return v == null ? 0 : v;
            }
        }
        catch (Exception ignored) {}
        return 0;
    }

    public int getFinalItem()
    {
        return finalItemId;
    }

    public int getHighlightDurationMs()
    {
        return (int) HIGHLIGHT_DURATION_MS;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!isAnimating)
        {
            minimiseButtonRect = null;
            return null;
        }

        if (rollOverlayMinimised)
        {
            return renderMinimised(g);
        }

        // RollBound selection rendering path
        if (choiceEventId != null)
        {
            return renderChoice(g);
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        final long nowNs = System.nanoTime();
        final long elapsedMs = (nowNs - rollStartNs) / 1_000_000L;
        final boolean inHighlightPhase = (elapsedMs > rollDurationMs);

        if (elapsedMs > rollDurationMs + HIGHLIGHT_DURATION_MS)
        {
            isAnimating = false;
            return null;
        }

        // dt (clamped)
        float dt = 0f;
        if (lastUpdateNanos != 0L)
        {
            dt = (nowNs - lastUpdateNanos) / 1_000_000_000f;
            if (dt > MAX_DT) dt = MAX_DT;
        }
        lastUpdateNanos = nowNs;

        // Progress 0..1 across the spin duration.
        final float t = (rollDurationMs > 0) ? Math.min(1f, elapsedMs / (float) rollDurationMs) : 1f;
        // Smooth ease-out (cubic)
        final float eased = 1f - (float) Math.pow(1f - t, 3);
        scrollPx = maxScrollPx * eased;

        // Viewport + box geometry
        final int vpX = client.getViewportXOffset();
        final int vpY = client.getViewportYOffset();
        final int vpWidth = client.getViewportWidth();
        final int centerX = vpX + (vpWidth / 2);
        final int boxTopY = vpY + OFFSET_TOP;

        final int totalIconsWidth = ICON_COUNT * ICON_W + (ICON_COUNT - 1) * SPACING;
        final int totalWidthWithBuffer = totalIconsWidth + EXTRA_WIDTH_BUFFER;
        final int boxWidth = totalWidthWithBuffer + OUTER_PAD * 2;
        final int boxHeight = ICON_H + OUTER_PAD * 2;
        final int boxLeftX = centerX - (boxWidth / 2) + BOX_SHIFT_X;

        // Content rect
        final int contentLeftX = boxLeftX + OUTER_PAD;
        final int innerWidth = boxWidth - OUTER_PAD * 2;
        final float contentCenterX = contentLeftX + innerWidth / 2f + CENTER_NUDGE_PX;
        final float middleIndex = ICON_COUNT / 2f;
        final float iconsLeftXF = contentCenterX - middleIndex * STEP - ICON_W / 2f;
        final int iconsY = boxTopY + OUTER_PAD;

        if (rollBoxImage != null)
        {
            final Composite prev = g.getComposite();
            g.setComposite(AlphaComposite.SrcOver.derive(0.95f));
            g.drawImage(rollBoxImage, boxLeftX, boxTopY, boxWidth, boxHeight, null);
            g.setComposite(prev);

            g.setColor(SHADE_BOTTOM);
            g.fillRect(boxLeftX, boxTopY + boxHeight - 2, boxWidth, 2);
            g.setColor(SHADE_TOP);
            g.drawLine(boxLeftX + 2, boxTopY + 2, boxLeftX + boxWidth - 3, boxTopY + 2);
        }

        final Shape oldClip = g.getClip();
        g.setClip(contentLeftX, boxTopY + OUTER_PAD, innerWidth, ICON_H);

        final int innerBoxXInset = FRAME_CONTENT_INSET;
        final int innerBoxYInset = FRAME_CONTENT_INSET;
        final int innerBoxW = ICON_W - innerBoxXInset * 2;
        final int innerBoxH = ICON_H - innerBoxYInset * 2;

        synchronized (reel)
        {
            final int startIndex = (int) Math.floor(scrollPx / STEP);
            final float offsetPx = scrollPx - (startIndex * STEP);

            for (int i = 0; i < DRAW_COUNT; i++)
            {
                final int idx = startIndex + i;
                if (idx < 0 || idx >= reel.size()) continue;

                final int itemId = reel.get(idx);
                final BufferedImage image = itemManager.getImage(itemId, 1, false);
                if (image == null) continue;

                final float drawXF = iconsLeftXF + i * STEP - offsetPx;
                final int drawX = Math.round(drawXF);

                if (iconFrameImage != null)
                {
                    g.drawImage(iconFrameImage, drawX, iconsY, ICON_W, ICON_H, null);
                }

                final int x = drawX + innerBoxXInset;
                final int y = iconsY + innerBoxYInset;
                g.drawImage(image, x, y, innerBoxW, innerBoxH, null);
            }

            if (inHighlightPhase)
            {
                final int centerIndex = ICON_COUNT / 2;
                final float baseXF = iconsLeftXF + centerIndex * STEP;
                final int baseX = Math.round(baseXF);

                final int glowW = (int) (ICON_W * 2.2);
                final int glowH = (int) (ICON_H * 2.2);
                final float cx = baseX + ICON_W / 2f;
                final float cy = iconsY + ICON_H / 2f;

                final RadialGradientPaint glow = new RadialGradientPaint(
                        new Point2D.Float(cx, cy),
                        glowW / 2f,
                        new float[]{0f, 1f},
                        new Color[]{
                                new Color(255, 255, 160, 150),
                                new Color(255, 255, 160, 0)
                        }
                );

                final Composite old = g.getComposite();
                g.setComposite(AlphaComposite.SrcOver.derive(0.85f));
                g.setPaint(glow);
                g.fill(new Ellipse2D.Float(cx - glowW / 2f, cy - glowH / 2f, glowW, glowH));
                g.setComposite(old);

                final float centerScale = 1.12f;
                final int innerBoxX = baseX + innerBoxXInset;
                final int innerBoxY = iconsY + innerBoxYInset;

                final int scaledW = (int) (innerBoxW * centerScale);
                final int scaledH = (int) (innerBoxH * centerScale);
                final int scaledX = innerBoxX + (innerBoxW - scaledW);
                final int scaledY = innerBoxY + (innerBoxH - scaledH);

                final BufferedImage centerImg = itemManager.getImage(this.finalItemId, 1, false);
                if (centerImg != null)
                {
                    g.drawImage(centerImg, scaledX, scaledY, scaledW, scaledH, null);
                }
            }
        }

        g.setClip(oldClip);
        drawMinimiseButton(g, false);
        return null;
    }

    private Dimension renderChoice(Graphics2D g)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        final long nowNs = System.nanoTime();
        final long elapsedMs = (nowNs - rollStartNs) / 1_000_000L;
        final boolean inHighlightPhase = (elapsedMs > rollDurationMs);

        // Selection is only enabled after the end-of-roll transition finishes.
        // NOTE: we no longer auto-hide after 5 seconds; the UI stays until a pick is made.

        // dt (clamped)
        float dt = 0f;
        if (lastUpdateNanos != 0L)
        {
            dt = (nowNs - lastUpdateNanos) / 1_000_000_000f;
            if (dt > MAX_DT) dt = MAX_DT;
        }
        lastUpdateNanos = nowNs;

        final float t = (rollDurationMs > 0) ? Math.min(1f, elapsedMs / (float) rollDurationMs) : 1f;
        final float eased = 1f - (float) Math.pow(1f - t, 3);

        // Layout: 5 vertical reels side-by-side while rolling, then collapse into a single row of selectable items.
        final int vpX = client.getViewportXOffset();
        final int vpY = client.getViewportYOffset();
        final int vpW = client.getViewportWidth();
        final int centerX = vpX + (vpW / 2);

        int reelCount;
        synchronized (choiceReels) { reelCount = choiceReels.size(); }
        if (reelCount <= 0)
        {
            return null;
        }

        // Rolling window height (3 visible rows)
        final int windowH = VISIBLE_ROWS * ICON_H + (VISIBLE_ROWS - 1) * SPACING;
        final int windowW = ICON_W;
        final int colGap = 16;

        // Selection/collapsed tile sizing (bigger, more like the reference tiles)
        // We allocate space for the label *inside* the tile at the bottom.
        final float selectScale = 1.5f;
        final int selW = Math.round(ICON_W * selectScale);
        final int selH = Math.round(ICON_H * selectScale) + 15; // makes box taller
        final int selGap = 30;
        final int labelPadY = 10;
        // Allow up to 3 lines for item names in selection tiles
        final int labelAreaH = 56;
        final int iconAreaH = Math.max(1, selH - labelAreaH);

        // Base position
        final int baseY = vpY + OFFSET_TOP;

        // Header text so teammates know who is choosing
        try
        {
            final String who = (choiceRollerName == null || choiceRollerName.trim().isEmpty()) ? "Someone" : choiceRollerName.trim();
            final String header = who + " to pick...";

            Font oldF = g.getFont();
            Font headerFont = oldF.deriveFont(Font.PLAIN, 18f);
            try
            {
                headerFont = net.runelite.client.ui.FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 18f);
            }
            catch (Throwable ignored) {}
            g.setFont(headerFont);
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(header);
            int x = centerX - w / 2;
            int y = baseY - 6;
            g.setColor(Color.BLACK);
            g.drawString(header, x + 1, y + 1);
            g.setColor(Color.WHITE);
            g.drawString(header, x, y);
            g.setFont(oldF);
        }
        catch (Exception ignored) {}

        if (!inHighlightPhase)
        {
            // Rolling phase: draw vertical reels (3 visible rows) side-by-side
            final int totalW = reelCount * windowW + (reelCount - 1) * colGap;
            final int startX = centerX - (totalW / 2);

            for (int idx = 0; idx < reelCount; idx++)
            {
                ReelState rs;
                synchronized (choiceReels) { rs = choiceReels.get(idx); }
                rs.scrollPx = rs.maxScrollPx * eased;

                final int colX = startX + idx * (windowW + colGap);
                final int colY = baseY;

                // Clear clickable rect during rolling
                rs.clickableCenterRect = null;

                // Clip to the 3-row window
                Shape prevClip = g.getClip();
                g.setClip(new Rectangle(colX, colY, windowW, windowH));

                final int startIndex = (int) Math.floor(rs.scrollPx / VSTEP);
                final float offset = rs.scrollPx - (startIndex * VSTEP);

                // Draw enough rows to cover the window with scrolling offset
                final int drawRows = VISIBLE_ROWS + 2;
                for (int r = 0; r < drawRows; r++)
                {
                    final int reelIndex = startIndex + r;
                    if (reelIndex < 0 || reelIndex >= rs.reel.size()) continue;

                    final int itemId = rs.reel.get(reelIndex);
                    final float yF = colY + r * VSTEP - offset;
                    final int y = Math.round(yF);

                    if (iconFrameImage != null)
                    {
                        g.drawImage(iconFrameImage, colX, y, ICON_W, ICON_H, null);
                    }

                    if (itemId > 0)
                    {
                        BufferedImage img = null;
                        try { img = itemManager.getImage(itemId, 1, false); } catch (Exception ignored) {}
                        if (img != null)
                        {
                            g.drawImage(img,
                                    colX + FRAME_CONTENT_INSET,
                                    y + FRAME_CONTENT_INSET,
                                    ICON_W - FRAME_CONTENT_INSET * 2,
                                    ICON_H - FRAME_CONTENT_INSET * 2,
                                    null);
                        }
                    }
                }

                g.setClip(prevClip);
            }
        }
        else
        {
            // Transition + selection phase.
            // Smoothly fade out reels and expand tiles into place.
            final long transElapsed = Math.max(0L, elapsedMs - rollDurationMs);
            final float transT = Math.min(1f, transElapsed / (float) CHOICE_TRANSITION_MS);
            final float transEased = 1f - (float) Math.pow(1f - transT, 3);

            // Only allow selection after the transition finishes.
            choiceAllowSelect = (transT >= 0.999f);

            // After a click/pick, keep the chosen tile visible a bit longer, then play an exit animation and end.
            if (chosenItemId != null)
            {
                long sincePickMs = (chosenAtNs > 0) ? ((nowNs - chosenAtNs) / 1_000_000L) : 0L;
                if (sincePickMs > (CHOICE_HOLD_AFTER_PICK_MS + CHOICE_EXIT_MS))
                {
                    isAnimating = false;
                    return null;
                }
            }

            final int totalSelW = reelCount * selW + (reelCount - 1) * selGap;
            final int startX = centerX - (totalSelW / 2);

            // Vertically centre the selectable row relative to the original rolling window.
            final int targetRowY = baseY + (windowH - selH) / 2;
            final int rowY = Math.round(baseY + (targetRowY - baseY) * transEased);

            // During the transition, draw the final reels fading out.
            if (transT < 1f)
            {
                final Composite oldComp = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, 1f - transEased)));

                final int totalW = reelCount * windowW + (reelCount - 1) * colGap;
                final int startReelX = centerX - (totalW / 2);

                for (int idx = 0; idx < reelCount; idx++)
                {
                    ReelState rs;
                    synchronized (choiceReels) { rs = choiceReels.get(idx); }
                    rs.scrollPx = rs.maxScrollPx; // snap to end

                    final int colX = startReelX + idx * (windowW + colGap);
                    final int colY = baseY;
                    rs.clickableCenterRect = null;

                    Shape prevClip = g.getClip();
                    g.setClip(new Rectangle(colX, colY, windowW, windowH));

                    final int startIndex = (int) Math.floor(rs.scrollPx / VSTEP);
                    final float offset = rs.scrollPx - (startIndex * VSTEP);
                    final int drawRows = VISIBLE_ROWS + 2;
                    for (int r = 0; r < drawRows; r++)
                    {
                        final int reelIndex = startIndex + r;
                        if (reelIndex < 0 || reelIndex >= rs.reel.size()) continue;

                        final int itemId = rs.reel.get(reelIndex);
                        final float yF = colY + r * VSTEP - offset;
                        final int y = Math.round(yF);

                        if (iconFrameImage != null)
                        {
                            g.drawImage(iconFrameImage, colX, y, ICON_W, ICON_H, null);
                        }

                        if (itemId > 0)
                        {
                            BufferedImage img = null;
                            try { img = itemManager.getImage(itemId, 1, false); } catch (Exception ignored) {}
                            if (img != null)
                            {
                                g.drawImage(img,
                                        colX + FRAME_CONTENT_INSET,
                                        y + FRAME_CONTENT_INSET,
                                        ICON_W - FRAME_CONTENT_INSET * 2,
                                        ICON_H - FRAME_CONTENT_INSET * 2,
                                        null);
                            }
                        }
                    }
                    g.setClip(prevClip);
                }

                g.setComposite(oldComp);
            }

            Font oldFont = g.getFont();

            Font choiceFont = net.runelite.client.ui.FontManager
                    .getRunescapeFont()
                    .deriveFont(13f);

            g.setFont(choiceFont);
            FontMetrics fm = g.getFontMetrics();

            // Tiles fade/scale in
            final float tileAlpha = Math.min(1f, transEased);
            final Composite baseComp = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, tileAlpha));

            float exitT = 0f;
            if (chosenItemId != null && chosenAtNs > 0)
            {
                long sincePickMs = (nowNs - chosenAtNs) / 1_000_000L;
                long animMs = Math.max(0L, sincePickMs - CHOICE_HOLD_AFTER_PICK_MS);
                exitT = Math.min(1f, animMs / (float) CHOICE_EXIT_MS);
            }
            final float exitEased = 1f - (float) Math.pow(1f - exitT, 3);

            for (int idx = 0; idx < reelCount; idx++)
            {
                ReelState rs;
                synchronized (choiceReels) { rs = choiceReels.get(idx); }

                final int baseX = startX + idx * (selW + selGap);
                final int baseY2 = rowY;

                float scale = (0.95f + 0.60f * transEased);
				// subtle hover grow (only when selectable and not chosen yet)
				if (chosenItemId == null && choiceAllowSelect && hoveredItemId != null && hoveredItemId == rs.finalItemId)
				{
					long dtNs2 = System.nanoTime() - hoverChangedNs;
					float ht = Math.min(1f, dtNs2 / 120_000_000f); // 120ms ramp
					float hoverEased = 1f - (float) Math.pow(1f - ht, 3);
					scale *= (1.0f + 0.06f * hoverEased); // ~6% grow
				}
                if (chosenItemId != null && rs.finalItemId == chosenItemId)
                {
                    scale *= (1.0f + 0.18f * exitEased);
                }
                final int w = Math.round(selW * scale);
                final int h = Math.round(selH * scale);
                final int x = baseX + (selW - w) / 2;
                final int y = baseY2 + (selH - h) / 2;

                if (chosenItemId != null)
                {
                    float a = tileAlpha;
                    if (rs.finalItemId != chosenItemId)
                    {
                        a = Math.max(0f, tileAlpha * (1f - exitEased));
                    }
                    else
                    {
                        a = Math.max(0f, tileAlpha * (1f - exitEased * 0.15f));
                    }
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
                }

                // Clickable rect for selection
                rs.clickableCenterRect = new Rectangle(x, y, w, h);

                // Frame (use new-frame.png for selection tiles; fallback to icon_slot.png)
                BufferedImage frame = (selectionFrameImage != null) ? selectionFrameImage : iconFrameImage;
                if (frame != null)
                {
                    g.drawImage(frame, x, y, w, h, null);
                }
                // Icon (square, centred in the icon area above the label)
                BufferedImage img = null;
                try { img = itemManager.getImage(rs.finalItemId, 1, false); } catch (Exception ignored) {}
                if (img != null)
                {
                    final int selInset = 4;
                    final int iconAreaTop = y + selInset;
                    final int iconAreaBottom = y + h - labelAreaH - selInset;
                    final int iconAreaH2 = Math.max(1, iconAreaBottom - iconAreaTop);
                    final int iconAreaW2 = Math.max(1, w - selInset * 2);

                    final int iconSize = (int)(Math.min(iconAreaW2, iconAreaH2) * 0.85f);
                    final int iconX = x + (w - iconSize) / 2;

                    final int topOffset = 6; // <-- ADD THIS
                    final int iconY = iconAreaTop + (iconAreaH2 - iconSize) / 2 + topOffset; // <-- CHANGE THIS

                    g.drawImage(img, iconX, iconY, iconSize, iconSize, null);
                }

// Name INSIDE tile (lowercase) - wrapped to max 2 lines
                String name = "";
                try
                {
                    name = itemManager.getItemComposition(rs.finalItemId).getName();
                }
                catch (Exception ignored) {}
                if (name != null) name = name.toLowerCase();
                if (name == null) name = "";

                final int maxTextW = Math.max(1, w - 12);
                final int maxLines = 3;

                java.util.List<String> lines = new java.util.ArrayList<>(2);
                String[] words = name.split(" ");
                String current = "";
                for (String word : words)
                {
                    if (word.isEmpty()) continue;
                    String candidate = current.isEmpty() ? word : (current + " " + word);
                    if (fm.stringWidth(candidate) <= maxTextW)
                    {
                        current = candidate;
                    }
                    else
                    {
                        if (!current.isEmpty())
                        {
                            lines.add(current);
                            current = word;
                            if (lines.size() == maxLines - 1) break;
                        }
                        else
                        {
                            // single very long word: hard cut with ellipsis
                            String ell = "...";
                            int cut = word.length();
                            while (cut > 0 && fm.stringWidth(word.substring(0, cut) + ell) > maxTextW) cut--;
                            current = (cut <= 0) ? ell : (word.substring(0, cut) + ell);
                            lines.add(current);
                            current = "";
                            break;
                        }
                    }
                }
                if (lines.size() < maxLines && !current.isEmpty())
                {
                    lines.add(current);
                }
                // Ellipsise last line if still too long or if we skipped remaining words
                if (!lines.isEmpty())
                {
                    int lastIdx = lines.size() - 1;
                    String last = lines.get(lastIdx);
                    if (fm.stringWidth(last) > maxTextW)
                    {
                        String ell = "...";
                        int cut = last.length();
                        while (cut > 0 && fm.stringWidth(last.substring(0, cut) + ell) > maxTextW) cut--;
                        last = (cut <= 0) ? ell : (last.substring(0, cut) + ell);
                        lines.set(lastIdx, last);
                    }
                }

                // Draw lines inside the label area at the bottom of the tile
                int lineHeight = fm.getAscent();
                int totalTextH = lines.size() * lineHeight;
                int labelTop = y + h - labelAreaH;
                int textBaseY = labelTop + (labelAreaH - totalTextH) / 2 + fm.getAscent();

                for (int li = 0; li < lines.size(); li++)
                {
                    String line = lines.get(li);
                    int textW = fm.stringWidth(line);
                    int textX = x + (w - textW) / 2;
                    int textY = textBaseY + li * lineHeight;

                    g.setColor(Color.BLACK);
                    g.drawString(line, textX + 1, textY + 1);
                    g.setColor(Color.WHITE);
                    g.drawString(line, textX, textY);
                }

// Selection glow
                if (chosenItemId != null && rs.finalItemId == chosenItemId)
                {
                    float pulse = 0.5f;
                    if (chosenAtNs > 0)
                    {
                        double secs = (nowNs - chosenAtNs) / 1_000_000_000.0;
                        pulse = (float) (0.5 + 0.5 * Math.sin(secs * 10.0));
                    }
                    Composite old = g.getComposite();
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f + 0.25f * pulse));
                    g.setColor(new Color(255, 215, 0));
                    int pad = 10;
                    g.setStroke(new BasicStroke(3f));
                    g.drawRoundRect(x - pad, y - pad, w + pad * 2, h + pad * 2, 14, 14);
                    g.setComposite(old);
                }
            }

            g.setComposite(baseComp);
            g.setFont(oldFont);
        }

        drawMinimiseButton(g, false);
        return null;
    }
}
