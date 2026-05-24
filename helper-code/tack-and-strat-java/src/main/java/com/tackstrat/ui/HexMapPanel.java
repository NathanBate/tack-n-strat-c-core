package com.tackstrat.ui;

import com.tackstrat.model.City;
import com.tackstrat.model.CityFocus;
import com.tackstrat.model.GameSession;
import com.tackstrat.model.HexCoord;
import com.tackstrat.model.Terrain;
import com.tackstrat.model.TileImprovement;
import com.tackstrat.model.Unit;
import com.tackstrat.model.UnitKind;
import com.tackstrat.model.Weather;
import com.tackstrat.model.WildAnimal;
import com.tackstrat.graphics.GraphicResolvedAsset;
import com.tackstrat.graphics.GraphicRuntime;
import com.tackstrat.graphics.GraphicSlotIds;
import com.tackstrat.persistence.SeatCameraSnap;
import com.tackstrat.persistence.UiSaveState;
import com.tackstrat.perf.EdtProfiler;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/** Pan/zoom hex map with fog of war, textured tiles, units and cities. */
final class HexMapPanel extends JPanel {

    private record PerSeatCamera(double scale, double offsetX, double offsetY) {}
    /** rank is used only for marker shading; travelTurns is settler path ETA to reach the tile. */
    private record SettleHint(int score, int rank, int travelTurns, List<String> positives, List<String> negatives) {}

    private static final double HEX_R = 36.0;
    /** Direction from hex center toward the fake sun (upper-left); rim shading uses the same light. */
    private static final double LIGHT_DIR_X = -0.7071067811865476;
    private static final double LIGHT_DIR_Y = -0.7071067811865476;
    private static final int MINIMAP_W = 170;
    private static final int MINIMAP_H = 128;
    private static final int MINIMAP_EDGE_PAD = 16;
    private static final double MIN_SCALE = 0.45;
    private static final double MAX_SCALE = 2.4;
    private static final double ZOOM_STEP_PER_WHEEL_NOTCH = 1.12;
    private static final double TRACKPAD_PAN_PX_PER_STEP = 38.0;
    /** Below this world zoom, skip heavy blending / shore extras (still draw core map). */
    private static final double LOD_DETAIL_MIN_SCALE = 0.58;
    private static final int EDGE_PAN_MARGIN_PX = 22;
    private static final int EDGE_PAN_STEP_PX = 24;
    private static final int CURSOR_KEEP_VISIBLE_MARGIN_PX = 120;

    private GameSession session;
    private final SelectionListener listener;
    private final Consumer<String> gameLog;
    private final Consumer<String> hoverInfo;

    private int selectedUnitId = -1;
    private int selectedCityId = -1;
    private boolean moveCommandMode;
    private List<HexCoord> projectedPath = List.of();
    private int projectedTurns;
    private HexCoord moveCursor;
    private boolean moveCursorLocked;

    /** View transform: world (hex pixels) → screen pixels = world * scale + offset. */
    private double viewScale = 1.0;
    private double viewOffsetX = 0.0;
    private double viewOffsetY = 0.0;

    /** Hotseat: each seat keeps its own pan/zoom until they play again (same {@link GameSession} instance). */
    private GameSession cameraSession;

    private final Map<Integer, PerSeatCamera> cameraBySeat = new HashMap<>();
    /** Which seat's camera values are currently loaded into {@link #viewScale} / offsets. */
    private int cameraSeat = -1;

    /** After {@link #bind(GameSession)}, optionally center on a unit when no saved camera existed for that seat. */
    private boolean pendingCenterAfterBind;

    /** Cached world-space bounds of the whole map. */
    private double worldMinX, worldMinY, worldMaxX, worldMaxY;

    private Point lastDragPoint;
    private Point lastMousePoint;
    private Timer edgePanTimer;
    private Timer flashTimer;
    /** Animates subtle glints on ocean hexes (radians, grows unbounded; sin-based). */
    private double waterShimmerPhase;
    private final Timer waterShimmerTimer;
    /** Selection ring / low-HP UI rhythm (radians). */
    private double uiPulsePhase;
    /** Procedural terrain deco sway (radians). */
    private double decorWindPhase;
    /** Wind-streak animation phase (grows unbounded; used in paintWindClusters). */
    private double windPhase;
    /** Fraction of water hexes that run full {@link #paintWaterShimmer} (stable hash by coord; rest use base water only). */
    private static final int SHIMMER_WATER_PERCENT = 35;

    /** True when zoomed in enough for coast foam, edge softening, fog banks, etc. */
    private boolean lodDetail = true;
    private long flashUntilMs;
    private long flashStartMs;
    private HexCoord flashTile;
    private HexCoord hoveredHex;
    private String toastText = "";
    private long toastUntilMs = 0L;
    private PixelSprite citySprite;
    private PixelSprite scoutSprite;
    private PixelSprite settlerSprite;
    private BufferedImage cityPng;
    private BufferedImage scoutPng;
    private BufferedImage settlerPng;
    private BufferedImage farmPng;
    private PixelSprite farmSprite;
    private BufferedImage warriorPng;
    private PixelSprite warriorSprite;
    private BufferedImage farmerPng;
    private PixelSprite farmerSprite;
    private BufferedImage builderPng;
    private PixelSprite builderSprite;
    /** True when the currently loaded unit art comes from user library upload (not bundled defaults). */
    private boolean scoutUsesLibraryArt;
    private boolean settlerUsesLibraryArt;
    private boolean warriorUsesLibraryArt;
    private boolean farmerUsesLibraryArt;
    private boolean builderUsesLibraryArt;
    private BufferedImage minePng;
    private PixelSprite mineSprite;
    private BufferedImage wildlifePng;
    private PixelSprite wildlifeSprite;
    private BufferedImage terrainWaterPng;
    private BufferedImage terrainGrassPng;
    private BufferedImage terrainPlainsPng;
    private BufferedImage terrainDesertPng;
    private BufferedImage terrainHillPng;
    private BufferedImage terrainForestPng;
    private BufferedImage terrainMountainPng;
    private int minQ, maxQ, minR, maxR;

    /** HUD: weather color key (Settings + keybind). */
    private boolean weatherLegendVisible = true;
    private boolean settlerHintsVisible = true;
    private boolean claimLegendVisible = true;
    private boolean minimapTintOwnClaimsOnly;
    private boolean hotkeysHelpVisible = false;
    /** Memoize expensive settler lens scoring while selection + session stay stable. */
    private Map<HexCoord, SettleHint> settleHintCache;
    private int settleHintCacheSettlerId = -1;
    private GameSession settleHintCacheSession;
    private int settleWeightFood = 3;
    private int settleWeightProduction = 3;
    private int settleWeightGold = 2;
    private int settleWeightTravel = 3;
    private int settleWeightRivalPressure = 2;

    HexMapPanel(SelectionListener listener, Consumer<String> gameLog, Consumer<String> hoverInfo) {
        super(null);
        this.listener = listener;
        this.gameLog = Objects.requireNonNullElseGet(gameLog, () -> s -> {});
        this.hoverInfo = Objects.requireNonNullElseGet(hoverInfo, () -> s -> {});
        setOpaque(true);
        setBackground(UiTheme.BG_DEEP);
        setFocusable(true);
        setToolTipText(" ");
        reloadGraphicAssetsFromDisk();

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isMiddleMouseButton(e)
                        || (SwingUtilities.isLeftMouseButton(e) && e.isShiftDown())) {
                    lastDragPoint = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                lastDragPoint = null;
                setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    clearSelection();
                    listener.selectionChanged();
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown()) {
                    if (!handleMiniMapClick(e.getX(), e.getY())) {
                        handleClick(e.getX(), e.getY());
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                lastMousePoint = null;
                stopEdgePanTimer();
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (lastDragPoint == null) {
                    return;
                }
                int dx = e.getX() - lastDragPoint.x;
                int dy = e.getY() - lastDragPoint.y;
                viewOffsetX += dx;
                viewOffsetY += dy;
                lastDragPoint = e.getPoint();
                clampView();
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (session == null) return;
                lastMousePoint = e.getPoint();
                if (moveCommandMode) {
                    maybePanAtScreenEdge(e.getX(), e.getY());
                    ensureEdgePanTimer();
                }
                HexCoord h = screenToHex(e.getX(), e.getY());
                hoveredHex = session.map().contains(h) ? h : null;
                hoverInfo.accept(hoveredHex == null ? " " : tileSummary(hoveredHex));
                if (!moveCursorLocked) {
                    moveCursor = hoveredHex;
                }
                updateProjectedPathFromHover();
                repaint();
            }
        });

        MouseWheelListener wheel = (MouseWheelEvent e) -> {
            double precise = e.getPreciseWheelRotation();
            if (precise == 0.0) return;
            boolean likelyTrackpad = Math.abs(precise) < 0.99;
            boolean zoomGesture = e.isMetaDown() || e.isControlDown() || e.isAltDown();
            if (likelyTrackpad && !zoomGesture) {
                if (e.isShiftDown()) {
                    viewOffsetX -= precise * TRACKPAD_PAN_PX_PER_STEP;
                } else {
                    viewOffsetY -= precise * TRACKPAD_PAN_PX_PER_STEP;
                }
                clampView();
                repaint();
                return;
            }
            double factor = Math.pow(ZOOM_STEP_PER_WHEEL_NOTCH, -precise);
            zoomAt(factor, e.getX(), e.getY());
        };
        addMouseWheelListener(wheel);

        waterShimmerTimer = new Timer(75, e -> {
            waterShimmerPhase += 0.16;
            uiPulsePhase += 0.065;
            decorWindPhase += 0.038;
            windPhase += 0.055;
            if (session != null && isShowing()) {
                repaint();
            }
        });
        waterShimmerTimer.start();
    }

    /** Move cost to enter {@code h} for the selected friendly unit, else base terrain cost only. */
    private int displayMoveCostIntoHex(HexCoord h) {
        Terrain t = session.terrainEffectiveAt(h);
        if (!t.passable()) {
            return t.movementCost();
        }
        if (selectedUnitId >= 0) {
            var uOpt = session.unitById(selectedUnitId);
            if (uOpt.isPresent() && uOpt.get().ownerSeat() == session.currentPlayer().seat()) {
                return session.movementCostForStep(uOpt.get(), h);
            }
        }
        return t.movementCost();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (session == null) return null;
        HexCoord h = screenToHex(event.getX(), event.getY());
        if (!session.map().contains(h)) return null;

        int seat = session.currentPlayer().seat();
        if (!session.visitedFor(seat).contains(h)) {
            return "Unknown tile";
        }
        var sb = new StringBuilder("<html>");
        sb.append(tileSummary(h)).append("<br>");
        Terrain t = session.terrainEffectiveAt(h);
        sb.append("<b>").append(terrainName(t)).append("</b>");
        if (t.passable()) {
            sb.append(" · Move ").append(displayMoveCostIntoHex(h));
        } else {
            sb.append(" · Impassable");
        }
        sb.append("<br>Hex ").append(h.q()).append(", ").append(h.r());
        sb.append("<br>Weather: ").append(session.weatherAt(h).label());

        var city = session.cityAt(h);
        if (city.isPresent()) {
            var c = city.get();
            sb.append("<br>City: ").append(c.name())
                    .append(" (").append(session.playerBySeat(c.ownerSeat()).name()).append(")");
        }

        var unit = session.unitAt(h);
        if (unit.isPresent() && (unit.get().ownerSeat() == seat || session.visibleFor(seat).contains(h))) {
            var u = unit.get();
            sb.append("<br>Unit: ").append(u.kind().displayName())
                    .append(" (").append(session.playerBySeat(u.ownerSeat()).name()).append(")")
                    .append(" HP ").append(u.hp()).append("/").append(u.maxHp());
        }
        session.wildAnimalAt(h).ifPresent(a -> sb.append("<br>Wildlife: ")
                .append(a.kind().label()).append(" HP ").append(a.hp()).append("/").append(a.kind().maxHp()));
        var settleHints = settleHintsForSelectedSettler(currentSelectedSettler(), session.visitedFor(seat));
        var settleHint = settleHints.get(h);
        if (settleHint != null) {
            sb.append("<br><b>Settlement recommendation</b>")
                    .append(" (score ").append(settleHint.score()).append(")")
                    .append("<br>Settler travel: ~").append(settleHint.travelTurns()).append(" turn")
                    .append(settleHint.travelTurns() == 1 ? "" : "s");
            if (!settleHint.positives().isEmpty()) {
                sb.append("<br><span style='color:#9fe0a8'>+ ")
                        .append(String.join(" · ", settleHint.positives()))
                        .append("</span>");
            }
            if (!settleHint.negatives().isEmpty()) {
                sb.append("<br><span style='color:#e7b39f'>- ")
                        .append(String.join(" · ", settleHint.negatives()))
                        .append("</span>");
            }
        }
        if (currentSelectedSettler() != null
                && session.terrainEffectiveAt(h).canFoundCityOn()
                && session.map().contains(h)) {
            appendCityYieldEstimateHtml(sb, h);
        }
        if (session.terrainEffectiveAt(h).passable()) {
            sb.append("<br><i>Worked yields</i> +").append(session.tileFoodYield(h)).append(" food · +")
                    .append(session.tileProductionYield(h)).append(" prod · +")
                    .append(session.tileGoldYield(h)).append(" gold");
            int soil = session.soilFertilityAt(h);
            if (soil > 0) {
                sb.append(" · soil +").append(soil);
            }
            int cult = session.cultivationAt(h);
            if (cult > 0) {
                sb.append(" · cultivated +").append(cult);
            }
            if (session.improvementAt(h) != TileImprovement.NONE) {
                sb.append(" · ").append(session.improvementAt(h).name());
            }
            String claimText = session.claimDebugAt(h);
            var claimOwner = session.claimedOwnerAt(h);
            String claimColor = claimOwner
                    .map(seatId -> htmlColor(UiTheme.PLAYER[seatId % UiTheme.PLAYER.length]))
                    .orElse("#a8b0bf");
            sb.append("<br><span style='color:")
                    .append(claimColor)
                    .append("'>")
                    .append(claimText)
                    .append("</span>");
        }
        sb.append("</html>");
        return sb.toString();
    }

    private static String htmlColor(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private String tileSummary(HexCoord h) {
        if (session == null || !session.map().contains(h)) return " ";
        int seat = session.currentPlayer().seat();
        if (!session.visitedFor(seat).contains(h)) return "Unknown tile";
        Terrain t = session.terrainEffectiveAt(h);
        var sb = new StringBuilder();
        sb.append(terrainName(t));
        if (t.passable()) {
            sb.append(" · Move ").append(displayMoveCostIntoHex(h));
        } else {
            sb.append(" · Impassable");
        }
        sb.append(" · ").append(h.q()).append(",").append(h.r());

        var city = session.cityAt(h);
        if (city.isPresent()) {
            var c = city.get();
            sb.append(" · City ").append(c.name());
        }
        if (t.passable()) {
            sb.append(" · ").append(session.claimDebugAt(h));
        }

        var unit = session.unitAt(h);
        if (unit.isPresent() && (unit.get().ownerSeat() == seat || session.visibleFor(seat).contains(h))) {
            var u = unit.get();
            sb.append(" · ").append(u.kind().displayName())
                    .append(" HP ").append(u.hp()).append("/").append(u.maxHp());
        }
        var settleHints = settleHintsForSelectedSettler(currentSelectedSettler(), session.visitedFor(seat));
        var settleHint = settleHints.get(h);
        if (settleHint != null) {
            sb.append(" · Settle ~").append(settleHint.travelTurns()).append("t");
        }
        return sb.toString();
    }

    String settlePreviewSummaryForHovered() {
        if (session == null || hoveredHex == null) return "";
        if (currentSelectedSettler() == null) return "";
        int seat = session.currentPlayer().seat();
        if (!session.visitedFor(seat).contains(hoveredHex)) return "";
        if (!session.terrainEffectiveAt(hoveredHex).canFoundCityOn()) return "";
        var y1 = session.previewCityYieldAt(hoveredHex, 1, CityFocus.BALANCED);
        var y2 = session.previewCityYieldAt(hoveredHex, 2, CityFocus.BALANCED);
        var y1r = session.previewCityYieldRealistic(hoveredHex, 1, CityFocus.BALANCED, seat);
        var y2r = session.previewCityYieldRealistic(hoveredHex, 2, CityFocus.BALANCED, seat);
        var hint = settleHintsForSelectedSettler(currentSelectedSettler(), session.visitedFor(seat)).get(hoveredHex);
        var sb = new StringBuilder("<html><b>City yield estimate</b> (balanced, weather-adjusted)<br>");
        sb.append("<b>Optimistic</b> (any open neighbor): Pop 1 +").append(y1.food()).append("f +").append(y1.production())
                .append("p +").append(y1.gold()).append("g · Pop 2 +").append(y2.food()).append("f +").append(y2.production())
                .append("p +").append(y2.gold()).append("g<br>");
        sb.append("<b>Realistic today</b> (your claims only): Pop 1 +").append(y1r.food()).append("f +").append(y1r.production())
                .append("p +").append(y1r.gold()).append("g · Pop 2 +").append(y2r.food()).append("f +").append(y2r.production())
                .append("p +").append(y2r.gold()).append("g<br>");
        sb.append("<span style='color:#8e96a8;font-size:11px'>Realistic ignores neighbors clearly in another empire's claim.</span>");
        if (hint != null) {
            String pos = hint.positives().isEmpty() ? "none" : String.join(", ", hint.positives());
            String neg = hint.negatives().isEmpty() ? "none" : String.join(", ", hint.negatives());
            sb.append("<br><br><b>Recommendation</b> (score ").append(hint.score()).append(")<br>");
            sb.append("<b>Travel:</b> ~").append(hint.travelTurns()).append(" turn").append(hint.travelTurns() == 1 ? "" : "s")
                    .append("<br><span style='color:#9fe0a8'>+ ").append(pos).append("</span>")
                    .append("<br><span style='color:#e7b39f'>- ").append(neg).append("</span>");
        }
        sb.append("</html>");
        return sb.toString();
    }

    private void appendCityYieldEstimateHtml(StringBuilder sb, HexCoord h) {
        int seat = session.currentPlayer().seat();
        var y1 = session.previewCityYieldAt(h, 1, CityFocus.BALANCED);
        var y2 = session.previewCityYieldAt(h, 2, CityFocus.BALANCED);
        var y1r = session.previewCityYieldRealistic(h, 1, CityFocus.BALANCED, seat);
        var y2r = session.previewCityYieldRealistic(h, 2, CityFocus.BALANCED, seat);
        sb.append("<br><i>Est. city yields (balanced)</i><br>")
                .append("Pop 1: +").append(y1.food()).append("f +").append(y1.production()).append("p +").append(y1.gold()).append("g")
                .append(" &nbsp; <span style='color:#b8c0d0'>realistic +").append(y1r.food()).append("f +").append(y1r.production())
                .append("p +").append(y1r.gold()).append("g</span><br>")
                .append("Pop 2: +").append(y2.food()).append("f +").append(y2.production()).append("p +").append(y2.gold()).append("g")
                .append(" &nbsp; <span style='color:#b8c0d0'>realistic +").append(y2r.food()).append("f +").append(y2r.production())
                .append("p +").append(y2r.gold()).append("g</span><br>")
                .append("<span style='color:#9aa3b5;font-size:11px'>Optimistic = any open neighbor; realistic = tiles you can work today.</span>");
    }

    Map<HexCoord, Integer> debugSettleScoresFor(Unit settler, Set<HexCoord> visited) {
        var hints = settleHintsForSelectedSettler(settler, visited);
        var out = new HashMap<HexCoord, Integer>();
        for (var e : hints.entrySet()) {
            out.put(e.getKey(), e.getValue().score());
        }
        return out;
    }

    private Unit currentSelectedSettler() {
        if (session == null || selectedUnitId < 0) return null;
        var opt = session.unitById(selectedUnitId);
        if (opt.isEmpty()) return null;
        Unit u = opt.get();
        if (u.kind() != UnitKind.SETTLER) return null;
        if (u.ownerSeat() != session.currentPlayer().seat()) return null;
        return u;
    }

    private Map<HexCoord, SettleHint> settleHintsForSelectedSettler(Unit settler, Set<HexCoord> visited) {
        if (!settlerHintsVisible || session == null || settler == null || visited == null || visited.isEmpty()) return Map.of();
        if (settler.kind() != UnitKind.SETTLER || settler.ownerSeat() != session.currentPlayer().seat()) return Map.of();
        if (session == settleHintCacheSession && settler.id() == settleHintCacheSettlerId && settleHintCache != null) {
            return settleHintCache;
        }
        var scored = new ArrayList<Map.Entry<HexCoord, SettleHint>>();
        int seat = settler.ownerSeat();
        for (HexCoord c : visited) {
            if (!session.map().contains(c) || !session.terrainEffectiveAt(c).canFoundCityOn()) continue;
            if (session.cityAt(c).isPresent() || session.wildAnimalAt(c).isPresent()) continue;
            if (settler.coord().distanceTo(c) > 12) continue;
            var route = projectedPathTo(settler, c);
            if (route.path().isEmpty()) continue;
            int travelTurns = route.turns();
            if (travelTurns > 6) continue;
            boolean adjacentCity = false;
            int adjacentWater = 0;
            int adjacentHostileClaims = 0;
            int roughNeighborCount = 0;
            for (HexCoord n : c.neighbors()) {
                if (session.map().contains(n) && session.terrainEffectiveAt(n) == Terrain.WATER) adjacentWater++;
                if (session.claimedOwnerAt(n).filter(owner -> owner != seat).isPresent()) adjacentHostileClaims++;
                if (session.map().contains(n)) {
                    Terrain tn = session.terrainEffectiveAt(n);
                    if (tn == Terrain.FOREST || tn == Terrain.HILL) roughNeighborCount++;
                }
                if (session.cityAt(n).isPresent()) {
                    adjacentCity = true;
                    break;
                }
            }
            if (adjacentCity) continue;
            int food = session.tileFoodYield(c);
            int prod = session.tileProductionYield(c);
            int gold = session.tileGoldYield(c);
            int workable = 0;
            int rich = 0;
            for (HexCoord n : c.neighbors()) {
                if (!session.map().contains(n) || !session.terrainEffectiveAt(n).passable()) continue;
                workable++;
                int tf = session.tileFoodYield(n);
                int tp = session.tileProductionYield(n);
                int tg = session.tileGoldYield(n);
                food += tf;
                prod += tp;
                gold += tg;
                if (tf + tp + tg >= 4) rich++;
            }
            int nearEnemyPenalty = 0;
            int nearOwnBonus = 0;
            for (City ct : session.cities()) {
                int d = ct.coord().distanceTo(c);
                if (ct.ownerSeat() == seat) {
                    if (d <= 5) nearOwnBonus += 2;
                } else if (d <= 4) {
                    nearEnemyPenalty += (5 - d) * 2;
                }
            }
            Terrain center = session.terrainEffectiveAt(c);
            int terrainBonus = switch (center) {
                case HILL -> 4;
                case PLAINS, GRASS -> 2;
                case DESERT -> -2;
                default -> 0;
            };
            int coastlineBonus = Math.min(3, adjacentWater) * 2;
            int defenseBonus = center == Terrain.HILL ? 3 : 0;
            int score = food * settleWeightFood + prod * settleWeightProduction + gold * settleWeightGold
                    + rich * 2 + workable * 2
                    + nearOwnBonus + terrainBonus + coastlineBonus + defenseBonus
                    - nearEnemyPenalty
                    - travelTurns * settleWeightTravel
                    - adjacentHostileClaims * settleWeightRivalPressure;
            var pos = new ArrayList<String>();
            var neg = new ArrayList<String>();
            if (food >= 14) pos.add("strong food");
            if (prod >= 11) pos.add("strong production");
            if (gold >= 9) pos.add("good gold");
            if (rich >= 3) pos.add("many rich tiles");
            if (nearOwnBonus > 0) pos.add("supported by nearby city");
            if (adjacentWater >= 2) pos.add("coastal access");
            if (center == Terrain.HILL || roughNeighborCount >= 3) pos.add("defensible terrain");
            if (travelTurns <= 2) pos.add("quick to reach");
            if (workable <= 3) neg.add("few workable neighbors");
            if (food <= 9) neg.add("weak food");
            if (prod <= 7) neg.add("weak production");
            if (nearEnemyPenalty >= 4) neg.add("close to enemy city");
            if (travelTurns >= 4) neg.add("long travel time");
            if (center == Terrain.DESERT && food <= 10) neg.add("harsh center tile");
            if (adjacentHostileClaims >= 2) neg.add("pressure from nearby rival claims");
            if (session.claimedOwnerAt(c).filter(owner -> owner != seat).isPresent()) {
                neg.add("currently in rival claim");
                score -= (2 + settleWeightRivalPressure);
            }
            if (pos.isEmpty()) pos.add("balanced nearby yields");
            scored.add(Map.entry(c, new SettleHint(score, 0, travelTurns, pos, neg)));
        }
        scored.sort((a, b) -> Integer.compare(b.getValue().score(), a.getValue().score()));
        int keep = Math.min(6, scored.size());
        var out = new HashMap<HexCoord, SettleHint>();
        for (int i = 0; i < keep; i++) {
            var e = scored.get(i);
            var h = e.getValue();
            out.put(e.getKey(), new SettleHint(h.score(), i + 1, h.travelTurns(), h.positives(), h.negatives()));
        }
        settleHintCache = out;
        settleHintCacheSettlerId = settler.id();
        settleHintCacheSession = session;
        return out;
    }

    private void invalidateSettleHintCache() {
        settleHintCache = null;
        settleHintCacheSettlerId = -1;
        settleHintCacheSession = null;
    }

    private void paintSettleHints(Graphics2D g2, Map<HexCoord, SettleHint> hints) {
        for (var e : hints.entrySet()) {
            HexCoord c = e.getKey();
            SettleHint h = e.getValue();
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            double hx = cx;
            double hy = cy - HEX_R * 0.62;
            double rh = 10.0;
            Color goldFill = switch (h.rank()) {
                case 1 -> new Color(255, 214, 96, 252);
                case 2, 3 -> new Color(245, 198, 78, 248);
                default -> new Color(228, 182, 62, 245);
            };
            Color ink = new Color(72, 52, 22, 245);
            Color stem = new Color(212, 168, 52, 248);

            var head = new Ellipse2D.Double(hx - rh, hy - rh, rh * 2, rh * 2);
            var tail = new Path2D.Double();
            tail.moveTo(hx - rh * 0.58, hy + rh * 0.42);
            tail.lineTo(hx, hy + rh + 12);
            tail.lineTo(hx + rh * 0.58, hy + rh * 0.42);
            tail.closePath();

            // Soft shadow
            g2.translate(1.2, 1.4);
            g2.setColor(new Color(0, 0, 0, 55));
            g2.fill(head);
            g2.fill(tail);
            g2.translate(-1.2, -1.4);

            g2.setColor(stem);
            g2.fill(tail);
            g2.setColor(goldFill);
            g2.fill(head);

            g2.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(ink);
            g2.draw(head);
            g2.draw(tail);

            // Monument / civic glyph inside head (not an “up arrow”).
            double ix = hx - rh * 0.35;
            double iy = hy - rh * 0.28;
            g2.setColor(new Color(255, 252, 245, 248));
            g2.fill(new RoundRectangle2D.Double(ix, iy, rh * 0.7, rh * 0.85, 2.5, 2.5));
            g2.setColor(new Color(95, 72, 38, 240));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Double(ix, iy, rh * 0.7, rh * 0.85, 2.5, 2.5));
            g2.fill(new Rectangle2D.Double(ix + rh * 0.14, iy + rh * 0.22, rh * 0.14, rh * 0.22));
            g2.fill(new Rectangle2D.Double(ix + rh * 0.42, iy + rh * 0.22, rh * 0.14, rh * 0.22));
            g2.fill(new Rectangle2D.Double(ix + rh * 0.26, iy + rh * 0.52, rh * 0.18, rh * 0.28));

            // Travel ETA under pin; omit when zoomed out so the map stays readable.
            if (viewScale >= 0.68) {
                String eta = h.travelTurns() + "t";
                var etaFont = new Font(Font.SANS_SERIF, Font.BOLD, 9);
                g2.setFont(etaFont);
                var fm = g2.getFontMetrics(etaFont);
                float tw = fm.stringWidth(eta);
                float ty = (float) (hy + rh + 18);
                g2.setColor(new Color(16, 22, 30, 215));
                g2.fill(new RoundRectangle2D.Float(
                        (float) (hx - tw / 2f - 3),
                        ty - fm.getAscent() + 1f,
                        tw + 6f,
                        fm.getHeight() + 1f,
                        4f,
                        4f));
                g2.setColor(new Color(235, 242, 255, 248));
                g2.drawString(eta, (float) (hx - tw / 2f), ty);
            }
        }
    }

    void bind(GameSession session) {
        bind(session, UiSaveState.EMPTY);
    }

    void bind(GameSession session, UiSaveState ui) {
        UiSaveState uiSafe = ui == null ? UiSaveState.EMPTY : ui;
        if (this.cameraSession != session) {
            cameraBySeat.clear();
            this.cameraSession = session;
            importSeatCameras(uiSafe.seatCameras());
        }
        this.session = session;
        this.selectedUnitId = -1;
        this.selectedCityId = -1;
        this.moveCommandMode = false;
        this.projectedPath = List.of();
        this.projectedTurns = 0;
        this.moveCursor = null;
        this.moveCursorLocked = false;
        this.hoveredHex = null;
        hoverInfo.accept(" ");
        invalidateSettleHintCache();
        recomputeWorldBounds();

        int seat = session.currentPlayer().seat();
        cameraSeat = seat;
        PerSeatCamera saved = cameraBySeat.get(seat);
        if (saved != null) {
            viewScale = saved.scale();
            viewOffsetX = saved.offsetX();
            viewOffsetY = saved.offsetY();
            clampView();
            pendingCenterAfterBind = false;
        } else {
            fitToView();
            pendingCenterAfterBind = true;
        }
        reloadGraphicAssetsFromDisk();
        repaint();
    }

    /**
     * Call from the play screen once after layout so the first seat can focus their starting position.
     *
     * @return true when the map should jump to a unit/city (no prior camera for this seat).
     */
    boolean consumePendingCenterAfterBind() {
        boolean p = pendingCenterAfterBind;
        pendingCenterAfterBind = false;
        return p;
    }

    /**
     * Call when the seated player changes (begin turn). Stashes the outgoing seat's camera and restores the
     * incoming seat's last view, or fits the map if they have never played this session.
     *
     * @return true if a saved camera was restored (skip auto-center); false if {@link #fitToView()} was used.
     */
    boolean syncCameraToCurrentPlayer() {
        if (session == null) {
            return false;
        }
        int seat = session.currentPlayer().seat();
        if (seat == cameraSeat) {
            // Already showing this seat's pan/zoom (including right after bind).
            return true;
        }
        stashCameraForSeat(cameraSeat);
        cameraSeat = seat;
        PerSeatCamera saved = cameraBySeat.get(seat);
        if (saved != null) {
            viewScale = saved.scale();
            viewOffsetX = saved.offsetX();
            viewOffsetY = saved.offsetY();
            clampView();
            repaint();
            return true;
        }
        fitToView();
        repaint();
        return false;
    }

    private void stashCameraForSeat(int seat) {
        if (seat < 0 || session == null) {
            return;
        }
        cameraBySeat.put(seat, new PerSeatCamera(viewScale, viewOffsetX, viewOffsetY));
    }

    List<SeatCameraSnap> exportSeatCameras() {
        if (session == null) {
            return List.of();
        }
        stashCameraForSeat(cameraSeat);
        var out = new ArrayList<SeatCameraSnap>();
        for (var e : cameraBySeat.entrySet()) {
            var c = e.getValue();
            out.add(new SeatCameraSnap(e.getKey(), c.scale(), c.offsetX(), c.offsetY()));
        }
        out.sort(Comparator.comparingInt(SeatCameraSnap::seat));
        return List.copyOf(out);
    }

    private void importSeatCameras(List<SeatCameraSnap> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SeatCameraSnap s : list) {
            cameraBySeat.put(s.seat(), new PerSeatCamera(s.scale(), s.offsetX(), s.offsetY()));
        }
    }

    private Rectangle miniMapBounds() {
        return new Rectangle(getWidth() - MINIMAP_W - MINIMAP_EDGE_PAD, MINIMAP_EDGE_PAD, MINIMAP_W, MINIMAP_H);
    }

    /** Pan main view to match a click on the overview (top-right). */
    private boolean handleMiniMapClick(int mx, int my) {
        if (session == null || minQ > maxQ || minR > maxR) {
            return false;
        }
        Rectangle mm = miniMapBounds();
        if (!mm.contains(mx, my)) {
            return false;
        }
        double qRange = Math.max(1, maxQ - minQ + 1);
        double rRange = Math.max(1, maxR - minR + 1);
        double u = (mx - mm.x - 8) / (double) (MINIMAP_W - 16);
        double v = (my - mm.y - 8) / (double) (MINIMAP_H - 16);
        u = Math.max(0, Math.min(1, u));
        v = Math.max(0, Math.min(1, v));
        int tq = minQ + (int) Math.round(u * (maxQ - minQ));
        int tr = minR + (int) Math.round(v * (maxR - minR));
        HexCoord hit = new HexCoord(tq, tr);
        HexCoord dest =
                session.map().contains(hit)
                        ? hit
                        : session.map().allCells().stream()
                                .min(Comparator.comparingInt(c -> c.distanceTo(hit)))
                                .orElse(null);
        if (dest != null) {
            centerOn(dest);
            repaint();
            return true;
        }
        return false;
    }

    int selectedUnitId() {
        return selectedUnitId;
    }

    int selectedCityId() {
        return selectedCityId;
    }

    void selectUnitById(int unitId) {
        if (session == null) return;
        var opt = session.unitById(unitId);
        if (opt.isEmpty()) return;
        if (unitId != selectedUnitId) {
            invalidateSettleHintCache();
        }
        selectedUnitId = unitId;
        selectedCityId = -1;
        moveCursor = opt.get().coord();
        moveCursorLocked = false;
        updateProjectedPathFromHover();
        repaint();
    }

    void selectCityById(int cityId) {
        if (session == null) return;
        var opt = session.cityById(cityId);
        if (opt.isEmpty()) return;
        if (cityId != selectedCityId || selectedUnitId >= 0) {
            invalidateSettleHintCache();
        }
        selectedCityId = cityId;
        selectedUnitId = -1;
        moveCommandMode = false;
        projectedPath = List.of();
        projectedTurns = 0;
        moveCursor = null;
        moveCursorLocked = false;
        repaint();
    }

    void showToast(String text) {
        toastText = text == null ? "" : text;
        toastUntilMs = System.currentTimeMillis() + 3200;
        repaint();
    }

    void toggleMoveCommandMode() {
        moveCommandMode = !moveCommandMode;
        moveCursorLocked = false;
        moveCursor = null;
        if (!moveCommandMode) {
            stopEdgePanTimer();
        } else {
            ensureEdgePanTimer();
        }
        updateProjectedPathFromHover();
        repaint();
    }

    void setMoveCommandMode(boolean enabled) {
        moveCommandMode = enabled;
        moveCursorLocked = false;
        moveCursor = null;
        if (!moveCommandMode) {
            stopEdgePanTimer();
        } else {
            ensureEdgePanTimer();
        }
        updateProjectedPathFromHover();
        repaint();
    }

    boolean isMoveCommandMode() {
        return moveCommandMode;
    }

    boolean nudgeMoveCursor(int dq, int dr) {
        if (!moveCommandMode || session == null || selectedUnitId < 0) return false;
        var uOpt = session.unitById(selectedUnitId);
        if (uOpt.isEmpty()) return false;
        Unit u = uOpt.get();
        HexCoord base = moveCursor != null ? moveCursor : u.coord();
        HexCoord next = new HexCoord(base.q() + dq, base.r() + dr);
        if (!session.map().contains(next)) return false;
        moveCursor = next;
        moveCursorLocked = true;
        keepHexVisible(next, CURSOR_KEEP_VISIBLE_MARGIN_PX);
        updateProjectedPathFromHover();
        repaint();
        return true;
    }

    boolean commitProjectedMove() {
        if (!moveCommandMode || session == null || selectedUnitId < 0) return false;
        var uOpt = session.unitById(selectedUnitId);
        if (uOpt.isEmpty()) return false;
        Unit u = uOpt.get();
        HexCoord target = moveCursorLocked && moveCursor != null ? moveCursor : hoveredHex;
        if (target == null) return false;
        boolean moved = tryFollowProjectedPath(u, target);
        if (moved) {
            invalidateSettleHintCache();
            moveCommandMode = false;
            moveCursor = null;
            moveCursorLocked = false;
            stopEdgePanTimer();
            updateProjectedPathFromHover();
            repaint();
        }
        return moved;
    }

    void clearSelection() {
        invalidateSettleHintCache();
        selectedUnitId = -1;
        selectedCityId = -1;
        moveCommandMode = false;
        projectedPath = List.of();
        projectedTurns = 0;
        moveCursor = null;
        moveCursorLocked = false;
        stopEdgePanTimer();
        repaint();
    }

    void centerOn(HexCoord c) {
        if (session == null || c == null) return;
        double wx = axialToPixelX(c);
        double wy = axialToPixelY(c);
        viewOffsetX = getWidth() / 2.0 - wx * viewScale;
        viewOffsetY = getHeight() / 2.0 - wy * viewScale;
        clampView();
        repaint();
    }

    void fitToView() {
        if (session == null) return;
        int w = Math.max(100, getWidth());
        int h = Math.max(100, getHeight());
        var bounds = activeBoundsForCurrentPlayer();
        double dx = bounds.maxX - bounds.minX;
        double dy = bounds.maxY - bounds.minY;
        if (dx <= 0 || dy <= 0) return;
        double sx = (w - 80) / dx;
        double sy = (h - 80) / dy;
        double minScale = minAllowedScale();
        viewScale = Math.max(minScale, Math.min(MAX_SCALE, Math.min(sx, sy)));
        double cx = (bounds.minX + bounds.maxX) / 2;
        double cy = (bounds.minY + bounds.maxY) / 2;
        viewOffsetX = w / 2.0 - cx * viewScale;
        viewOffsetY = h / 2.0 - cy * viewScale;
        clampView();
    }

    void zoomAt(double factor, double anchorScreenX, double anchorScreenY) {
        double minScale = minAllowedScale();
        double newScale = Math.max(minScale, Math.min(MAX_SCALE, viewScale * factor));
        if (newScale == viewScale) return;
        // Anchor world point: (anchor - offset) / scale
        double wx = (anchorScreenX - viewOffsetX) / viewScale;
        double wy = (anchorScreenY - viewOffsetY) / viewScale;
        viewScale = newScale;
        viewOffsetX = anchorScreenX - wx * viewScale;
        viewOffsetY = anchorScreenY - wy * viewScale;
        clampView();
        repaint();
    }

    void zoomBy(double factor) {
        zoomAt(factor, getWidth() / 2.0, getHeight() / 2.0);
    }

    void panBy(int dx, int dy) {
        viewOffsetX += dx;
        viewOffsetY += dy;
        clampView();
        repaint();
    }

    private void maybePanAtScreenEdge(int sx, int sy) {
        int dx = 0;
        int dy = 0;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (sx <= EDGE_PAN_MARGIN_PX) dx = EDGE_PAN_STEP_PX;
        else if (sx >= w - EDGE_PAN_MARGIN_PX) dx = -EDGE_PAN_STEP_PX;
        if (sy <= EDGE_PAN_MARGIN_PX) dy = EDGE_PAN_STEP_PX;
        else if (sy >= h - EDGE_PAN_MARGIN_PX) dy = -EDGE_PAN_STEP_PX;
        if (dx == 0 && dy == 0) return;
        viewOffsetX += dx;
        viewOffsetY += dy;
        clampView();
    }

    private void ensureEdgePanTimer() {
        if (!moveCommandMode) return;
        if (edgePanTimer != null && edgePanTimer.isRunning()) return;
        edgePanTimer = new Timer(32, e -> {
            if (!moveCommandMode || lastMousePoint == null) {
                stopEdgePanTimer();
                return;
            }
            maybePanAtScreenEdge(lastMousePoint.x, lastMousePoint.y);
            repaint();
        });
        edgePanTimer.start();
    }

    private void stopEdgePanTimer() {
        if (edgePanTimer != null) {
            edgePanTimer.stop();
            edgePanTimer = null;
        }
    }

    private void keepHexVisible(HexCoord h, int marginPx) {
        if (h == null) return;
        double sx = axialToPixelX(h) * viewScale + viewOffsetX;
        double sy = axialToPixelY(h) * viewScale + viewOffsetY;
        int w = getWidth();
        int hgt = getHeight();
        if (w <= 0 || hgt <= 0) return;
        double shiftX = 0;
        double shiftY = 0;
        if (sx < marginPx) shiftX = marginPx - sx;
        else if (sx > w - marginPx) shiftX = (w - marginPx) - sx;
        if (sy < marginPx) shiftY = marginPx - sy;
        else if (sy > hgt - marginPx) shiftY = (hgt - marginPx) - sy;
        if (shiftX == 0 && shiftY == 0) return;
        viewOffsetX += shiftX;
        viewOffsetY += shiftY;
        clampView();
    }

    void flash(HexCoord c, int durationMs) {
        flashTile = c;
        flashStartMs = System.currentTimeMillis();
        flashUntilMs = flashStartMs + durationMs;
        if (flashTimer != null) flashTimer.stop();
        flashTimer = new Timer(40, e -> {
            if (System.currentTimeMillis() >= flashUntilMs) {
                ((Timer) e.getSource()).stop();
                flashTile = null;
            }
            repaint();
        });
        flashTimer.start();
    }

    private void clampView() {
        // Allow some over-scroll, but keep at least 200px of map on screen
        int w = getWidth();
        int h = getHeight();
        double minOffsetX = w - 200 - worldMaxX * viewScale;
        double maxOffsetX = 200 - worldMinX * viewScale;
        double minOffsetY = h - 200 - worldMaxY * viewScale;
        double maxOffsetY = 200 - worldMinY * viewScale;
        viewOffsetX = Math.min(maxOffsetX, Math.max(minOffsetX, viewOffsetX));
        viewOffsetY = Math.min(maxOffsetY, Math.max(minOffsetY, viewOffsetY));
    }

    private void recomputeWorldBounds() {
        worldMinX = Double.POSITIVE_INFINITY;
        worldMaxX = Double.NEGATIVE_INFINITY;
        worldMinY = Double.POSITIVE_INFINITY;
        worldMaxY = Double.NEGATIVE_INFINITY;
        minQ = Integer.MAX_VALUE;
        maxQ = Integer.MIN_VALUE;
        minR = Integer.MAX_VALUE;
        maxR = Integer.MIN_VALUE;
        if (session == null) return;
        double hw = Math.sqrt(3) * HEX_R;
        double hh = 2 * HEX_R;
        for (var c : session.map().allCells()) {
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            worldMinX = Math.min(worldMinX, cx - hw / 2);
            worldMaxX = Math.max(worldMaxX, cx + hw / 2);
            worldMinY = Math.min(worldMinY, cy - hh / 2);
            worldMaxY = Math.max(worldMaxY, cy + hh / 2);
            minQ = Math.min(minQ, c.q());
            maxQ = Math.max(maxQ, c.q());
            minR = Math.min(minR, c.r());
            maxR = Math.max(maxR, c.r());
        }
    }

    private Bounds activeBoundsForCurrentPlayer() {
        if (session == null) {
            return new Bounds(worldMinX, worldMinY, worldMaxX, worldMaxY);
        }
        int seat = session.currentPlayer().seat();
        var visited = session.visitedFor(seat);
        if (visited.isEmpty() || visited.size() >= session.map().allCells().size()) {
            return new Bounds(worldMinX, worldMinY, worldMaxX, worldMaxY);
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double hw = Math.sqrt(3) * HEX_R;
        double hh = 2 * HEX_R;
        for (var c : visited) {
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            minX = Math.min(minX, cx - hw / 2);
            maxX = Math.max(maxX, cx + hw / 2);
            minY = Math.min(minY, cy - hh / 2);
            maxY = Math.max(maxY, cy + hh / 2);
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY)) {
            return new Bounds(worldMinX, worldMinY, worldMaxX, worldMaxY);
        }
        // Small margin so the camera doesn't feel claustrophobic at the limit.
        double pad = HEX_R * 1.4;
        minX -= pad;
        maxX += pad;
        minY -= pad;
        maxY += pad;
        return new Bounds(minX, minY, maxX, maxY);
    }

    private double minAllowedScale() {
        if (session == null) return MIN_SCALE;
        int seat = session.currentPlayer().seat();
        int seen = session.visitedFor(seat).size();
        int all = session.map().allCells().size();
        if (seen <= 0 || seen >= all) {
            return MIN_SCALE;
        }
        Bounds b = activeBoundsForCurrentPlayer();
        double dx = b.maxX - b.minX;
        double dy = b.maxY - b.minY;
        if (dx <= 0 || dy <= 0) {
            return MIN_SCALE;
        }
        int w = Math.max(100, getWidth());
        int h = Math.max(100, getHeight());
        double sx = (w - 80) / dx;
        double sy = (h - 80) / dy;
        double fitDiscovered = Math.min(sx, sy);
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, fitDiscovered));
    }

    private record Bounds(double minX, double minY, double maxX, double maxY) {}

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(900, 600);
    }

    private static double axialToPixelX(HexCoord h) {
        return HEX_R * Math.sqrt(3) * (h.q() + h.r() / 2.0);
    }

    private static double axialToPixelY(HexCoord h) {
        return HEX_R * 1.5 * h.r();
    }

    private HexCoord screenToHex(double sx, double sy) {
        double wx = (sx - viewOffsetX) / viewScale;
        double wy = (sy - viewOffsetY) / viewScale;
        double fq = (Math.sqrt(3) / 3 * wx - 1.0 / 3 * wy) / HEX_R;
        double fr = (2.0 / 3 * wy) / HEX_R;
        return HexCoord.hexRound(fq, fr);
    }

    private HexCoord pickHexAtScreen(double sx, double sy) {
        HexCoord rounded = screenToHex(sx, sy);
        if (session == null || !session.map().contains(rounded)) return rounded;
        Point2D world = screenToWorld(sx, sy);
        if (hexPath(axialToPixelX(rounded), axialToPixelY(rounded)).contains(world)) {
            return rounded;
        }
        for (var n : rounded.neighbors()) {
            if (!session.map().contains(n)) continue;
            if (hexPath(axialToPixelX(n), axialToPixelY(n)).contains(world)) {
                return n;
            }
        }
        return rounded;
    }

    private void handleClick(double mx, double my) {
        if (session == null || session.isOver()) return;
        HexCoord h = pickHexAtScreen(mx, my);
        if (!session.map().contains(h)) return;

        int curSeat = session.currentPlayer().seat();
        var visible = session.visibleFor(curSeat);
        var visited = session.visitedFor(curSeat);

        // Unexplored tiles: only movement / attack / path projection (Civ-style queue into the fog).
        if (!visited.contains(h)) {
            trySelectedUnitInteract(h, visible);
            return;
        }

        // 1) Own city
        var cityHere = session.cityAt(h);
        if (cityHere.isPresent() && cityHere.get().ownerSeat() == curSeat) {
            selectedCityId = cityHere.get().id();
            selectedUnitId = -1;
            moveCommandMode = false;
            projectedPath = List.of();
            projectedTurns = 0;
            moveCursor = null;
            moveCursorLocked = false;
            updateProjectedPathFromHover();
            repaint();
            listener.selectionChanged();
            return;
        }

        // 2) Own unit
        var unitHere = session.unitAt(h);
        if (unitHere.isPresent() && unitHere.get().ownerSeat() == curSeat) {
            selectedUnitId = unitHere.get().id();
            selectedCityId = -1;
            moveCursor = unitHere.get().coord();
            moveCursorLocked = false;
            updateProjectedPathFromHover();
            repaint();
            listener.selectionChanged();
            return;
        }

        // 3) Selected unit acting on tile h
        if (trySelectedUnitInteract(h, visible)) {
            return;
        }

        clearSelection();
        listener.selectionChanged();
    }

    /**
     * Attack, step, or assign a multi-turn route toward {@code h}. Works on explored tiles and on fog
     * when projecting movement into the unknown.
     */
    private boolean trySelectedUnitInteract(HexCoord h, Set<HexCoord> visible) {
        if (selectedUnitId < 0) {
            return false;
        }
        var meOpt = session.unitById(selectedUnitId);
        if (meOpt.isEmpty()) {
            return false;
        }
        Unit me = meOpt.get();
        int curSeat = session.currentPlayer().seat();
        if (me.ownerSeat() != curSeat) {
            return false;
        }

        var unitHere = session.unitAt(h);
        // Adjacent enemy unit (visible) → attack
        if (visible.contains(h)
                && me.coord().distanceTo(h) == 1
                && unitHere.isPresent()
                && unitHere.get().ownerSeat() != curSeat) {
            var result = session.tryAttack(me.id(), h);
            result.ifPresent(s -> {
                flash(h, 350);
                gameLog.accept(s);
            });
            if (session.unitById(selectedUnitId).isEmpty()) {
                selectedUnitId = -1;
            }
            repaint();
            listener.selectionChanged();
            return true;
        }
        // Otherwise try move. If the click is farther than one tile, treat it as path assignment.
        boolean moved;
        if (moveCommandMode || me.coord().distanceTo(h) > 1) {
            moved = tryFollowProjectedPath(me, h);
        } else {
            moved = session.tryMoveUnit(me.id(), h);
        }
        if (moved) {
            invalidateSettleHintCache();
        }
        if (!moved && (me.kind() == UnitKind.SETTLER || me.kind() == UnitKind.SCOUT
                || me.kind() == UnitKind.FARMER || me.kind() == UnitKind.BUILDER
                || me.kind() == UnitKind.HUNTING_PARTY)) {
            // Keep selection for civilian/scout units to make repeated move attempts easier.
            flash(me.coord(), 160);
        }
        if (moved && moveCommandMode) {
            moveCommandMode = false;
            moveCursor = null;
            moveCursorLocked = false;
        }
        updateProjectedPathFromHover();
        repaint();
        listener.selectionChanged();
        return true;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        if (session == null) {
            g2.dispose();
            return;
        }

        long paintProfileStart = System.nanoTime();
        try {
            synchronized (session) {
                paintMapContent(g2);
            }
        } finally {
            if (EdtProfiler.enabled()) {
                EdtProfiler.recordMapPaintNanos(System.nanoTime() - paintProfileStart);
            }
        }

        g2.dispose();
    }

    /** Map + HUD drawn under {@code synchronized (session)} so AI worker ticks do not race readers. */
    private void paintMapContent(Graphics2D g2) {
        // Background ocean gradient
        var grad = new GradientPaint(0, 0, new Color(0x0a_12_1d),
                0, getHeight(), new Color(0x18_2a_44));
        g2.setPaint(grad);
        g2.fillRect(0, 0, getWidth(), getHeight());

        AffineTransform saved = g2.getTransform();
        g2.translate(viewOffsetX, viewOffsetY);
        g2.scale(viewScale, viewScale);

        int curSeat = session.currentPlayer().seat();
        Set<HexCoord> visible = session.visibleFor(curSeat);
        Set<HexCoord> visited = session.visitedFor(curSeat);
        lodDetail = viewScale >= LOD_DETAIL_MIN_SCALE;

        // 1) Tiles
        for (var c : session.map().allCells()) {
            Terrain t = session.terrainEffectiveAt(c);
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            boolean isVisible = visible.contains(c);
            boolean isVisited = visited.contains(c);
            paintTile(g2, c, t, cx, cy, isVisible, isVisited);
        }
        paintTerrainEdgeBlends(g2, visited, visible);
        paintCoastFoam(g2, visited, visible);
        paintShoreWaterDepth(g2, visited, visible);
        paintClaimBorders(g2, visible, visited);

        // 1b) Regional weather wash + badge on explored tiles
        for (var c : session.map().allCells()) {
            if (!visited.contains(c)) continue;
            Weather w = session.weatherAt(c);
            if (w == Weather.CLEAR) continue;
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            paintWeatherOverlay(g2, w, cx, cy, visible.contains(c));
        }

        paintRegionalFogBanks(g2, visited);
        paintWindClusters(g2, visited, visible);

        // 2) Move/attack highlights for selected unit
        Set<HexCoord> moves = new HashSet<>();
        Set<HexCoord> attacks = new HashSet<>();
        Unit selected = null;
        if (selectedUnitId >= 0) {
            var selectedOpt = session.unitById(selectedUnitId);
            if (selectedOpt.isPresent()) {
                selected = selectedOpt.get();
                long legalT0 = System.nanoTime();
                moves.addAll(session.legalMoves(selected));
                attacks.addAll(session.legalAttacks(selected));
                if (EdtProfiler.enabled()) {
                    EdtProfiler.recordLegalHighlightNanos(System.nanoTime() - legalT0);
                }
            }
        }
        boolean civilianMoveMode = selected != null
                && (selected.kind() == UnitKind.SETTLER
                        || selected.kind() == UnitKind.SCOUT
                        || selected.kind() == UnitKind.FARMER
                        || selected.kind() == UnitKind.BUILDER
                        || selected.kind() == UnitKind.HUNTING_PARTY);
        for (var c : moves) {
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            if (civilianMoveMode) {
                fillHexDirectionalTint(g2, cx, cy,
                        new Color(110, 228, 255, 108),
                        new Color(25, 110, 165, 72));
                drawHexOutline(g2, cx, cy, new Color(170, 245, 255, 235), 2.5f);
                drawHexOutline(g2, cx, cy, new Color(30, 140, 210, 210), 1.35f);
                g2.setColor(new Color(248, 255, 255, 238));
                g2.fill(new Ellipse2D.Double(cx - 4.5, cy - 4.5, 9, 9));
            } else {
                fillHexDirectionalTint(g2, cx, cy,
                        new Color(255, 238, 140, 118),
                        new Color(190, 120, 25, 68));
                drawHexOutline(g2, cx, cy, new Color(255, 225, 140, 235), 2.35f);
                drawHexOutline(g2, cx, cy, new Color(200, 140, 35, 195), 1.25f);
            }
        }
        for (var c : attacks) {
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            fillHexDirectionalTint(g2, cx, cy,
                    new Color(255, 120, 110, 125),
                    new Color(140, 25, 25, 85));
            drawHexOutline(g2, cx, cy, new Color(255, 190, 185, 228), 2.35f);
            drawHexOutline(g2, cx, cy, new Color(190, 35, 35, 215), 1.3f);
        }
        if (projectedPath.size() >= 2) {
            paintProjectedPath(g2, projectedPath, projectedTurns);
        }
        Map<HexCoord, SettleHint> settleHints = settleHintsForSelectedSettler(currentSelectedSettler(), visited);
        if (!settleHints.isEmpty()) {
            paintSettleHints(g2, settleHints);
        }

        // Selected unit tile wash (lit from NW, visible tiles only)
        if (selectedUnitId >= 0) {
            session.unitById(selectedUnitId).ifPresent(u -> {
                if (visited.contains(u.coord()) && visible.contains(u.coord())) {
                    paintHexFootprintGlow(g2, axialToPixelX(u.coord()), axialToPixelY(u.coord()));
                }
            });
        }

        // 2b) Ground selection ring (drawn before units so the unit disk reads on top)
        if (selectedUnitId >= 0) {
            session.unitById(selectedUnitId).ifPresent(u -> paintUnitSelectionRing(g2, u));
        }

        // 3) Cities — only render if visited (your own always visited; enemy if you've seen them)
        for (var ct : session.cities()) {
            if (visited.contains(ct.coord()) || ct.ownerSeat() == curSeat) {
                paintCity(g2, ct, visible.contains(ct.coord()));
            }
        }

        // 3b) Wild animals — visible like neutral units (only in line of sight)
        for (var beast : session.wildlife()) {
            if (!visible.contains(beast.coord())) {
                continue;
            }
            paintWildAnimal(g2, beast);
        }

        // 4) Units — only render if currently visible to current player
        for (var u : session.units()) {
            if (u.ownerSeat() == curSeat || visible.contains(u.coord())) {
                paintUnit(g2, u);
            }
        }

        // 5) Selection outline (city: hex rim; unit uses ring drawn earlier)
        if (selectedCityId >= 0) {
            session.cityById(selectedCityId).ifPresent(c -> {
                double cx = axialToPixelX(c.coord());
                double cy = axialToPixelY(c.coord());
                drawHexOutline(g2, cx, cy, new Color(255, 255, 255, 235), 3.2f);
            });
        }
        if (hoveredHex != null && session.map().contains(hoveredHex)) {
            int seat = session.currentPlayer().seat();
            boolean hoverKnown = session.visitedFor(seat).contains(hoveredHex);
            boolean outlineFogTarget = false;
            if (selectedUnitId >= 0) {
                var su = session.unitById(selectedUnitId);
                outlineFogTarget = su.isPresent() && su.get().ownerSeat() == seat;
            }
            if (hoverKnown || outlineFogTarget) {
                double cx = axialToPixelX(hoveredHex);
                double cy = axialToPixelY(hoveredHex);
                paintHexHoverHighlight(g2, cx, cy, hoverKnown);
            }
        }

        // 6) Combat / feedback flash (expanding rim + easing core)
        if (flashTile != null) {
            double cx = axialToPixelX(flashTile);
            double cy = axialToPixelY(flashTile);
            paintCombatFlash(g2, cx, cy);
        }

        g2.setTransform(saved);

        paintViewportVignette(g2);
        paintTimeOfDayOverlay(g2);

        paintHud(g2);
    }

    boolean isWeatherLegendVisible() {
        return weatherLegendVisible;
    }

    void setWeatherLegendVisible(boolean visible) {
        this.weatherLegendVisible = visible;
    }

    boolean isSettlerHintsVisible() {
        return settlerHintsVisible;
    }

    void setSettlerHintsVisible(boolean visible) {
        this.settlerHintsVisible = visible;
    }

    void setClaimLegendVisible(boolean visible) {
        this.claimLegendVisible = visible;
    }

    void setMinimapTintOwnClaimsOnly(boolean onlyOwn) {
        this.minimapTintOwnClaimsOnly = onlyOwn;
        repaint();
    }

    void setSettlerHintWeights(int food, int production, int gold, int travel, int rivalPressure) {
        this.settleWeightFood = clampWeight(food);
        this.settleWeightProduction = clampWeight(production);
        this.settleWeightGold = clampWeight(gold);
        this.settleWeightTravel = clampWeight(travel);
        this.settleWeightRivalPressure = clampWeight(rivalPressure);
        invalidateSettleHintCache();
    }

    private void paintHud(Graphics2D g2) {
        paintWeatherLegend(g2);
        var f = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
        g2.setFont(f);
        g2.setColor(new Color(255, 255, 255, 150));
        var fm = g2.getFontMetrics(f);
        String hint = "Wheel: zoom  ·  Drag: pan  ·  Arrows: pan  ·  +/-: zoom  ·  Shift+F: fit  ·  Shift+E: auto-explore  ·  F1: hotkeys";
        int hw = fm.stringWidth(hint);
        int y = getHeight() - 12;
        int hx = Math.max(12, (getWidth() - hw) / 2);
        if (hx + hw > getWidth() - 12) {
            hx = Math.max(12, getWidth() - hw - 12);
        }
        g2.drawString(hint, hx, y);
        paintClaimLegend(g2);
        paintMiniMap(g2);
        paintToast(g2);
        paintHotkeysHelpOverlay(g2);
    }

    boolean isHotkeysHelpVisible() {
        return hotkeysHelpVisible;
    }

    void setHotkeysHelpVisible(boolean visible) {
        this.hotkeysHelpVisible = visible;
        repaint();
    }

    void toggleHotkeysHelpOverlay() {
        this.hotkeysHelpVisible = !this.hotkeysHelpVisible;
        repaint();
    }

    private void paintHotkeysHelpOverlay(Graphics2D g2) {
        if (!hotkeysHelpVisible) return;
        Keybinds kb = Keybinds.load();
        var entries = new ArrayList<String>();
        for (String action : Keybinds.actionOrder()) {
            List<String> specs = kb.keySpecsFor(action);
            if (specs.isEmpty()) continue;
            entries.add(Keybinds.labelFor(action) + ": " + String.join(", ", specs));
        }
        entries.add("Esc: cancel move / selection, then game menu");
        int pad = 14;
        int lineH = 15;
        int titleH = 18;
        int colGap = 22;
        int colW = 300;
        int leftCol = (entries.size() + 1) / 2;
        int rowCount = leftCol;
        int boxW = pad * 2 + colW * 2 + colGap;
        int boxH = pad * 2 + titleH + lineH * rowCount;
        int bx = (getWidth() - boxW) / 2;
        int by = (getHeight() - boxH) / 2;
        g2.setColor(new Color(10, 14, 22, 230));
        g2.fillRoundRect(bx, by, boxW, boxH, 12, 12);
        g2.setColor(new Color(90, 104, 128, 235));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRoundRect(bx, by, boxW, boxH, 12, 12);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g2.setColor(new Color(230, 236, 248, 250));
        g2.drawString("Hotkeys (remap in Settings → Controls)", bx + pad, by + pad + 13);
        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g2.setColor(new Color(200, 210, 226, 248));
        int y0 = by + pad + titleH + 10;
        for (int i = 0; i < entries.size(); i++) {
            int col = i < leftCol ? 0 : 1;
            int row = col == 0 ? i : i - leftCol;
            int xCol = bx + pad + col * (colW + colGap);
            int y = y0 + row * lineH;
            g2.drawString(entries.get(i), xCol, y);
        }
    }

    private void paintClaimLegend(Graphics2D g2) {
        if (session == null || !claimLegendVisible) return;
        int x = 14;
        int y = 16;
        int w = 236;
        int h = 62;
        g2.setColor(new Color(12, 18, 26, 205));
        g2.fillRoundRect(x, y, w, h, 10, 10);
        g2.setColor(new Color(72, 84, 102, 210));
        g2.drawRoundRect(x, y, w, h, 10, 10);
        g2.setColor(new Color(206, 216, 228, 230));
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g2.drawString("Territory borders", x + 10, y + 18);
        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g2.setColor(new Color(180, 194, 210, 218));
        g2.drawString("Dashed border color = owner. Double edge = pressure.", x + 10, y + 34);
        g2.setColor(new Color(180, 194, 210, 202));
        g2.drawString("Claimed tiles affect city working and expansion.", x + 10, y + 49);
    }

    /** Key for map tint / badge colors (tile tooltip still spells out the active weather). */
    private void paintWeatherLegend(Graphics2D g2) {
        if (!weatherLegendVisible) {
            return;
        }
        Weather[] wx = {
            Weather.RAIN,
            Weather.DROUGHT,
            Weather.STORM,
            Weather.COLD_SNAP,
            Weather.HEAT_WAVE,
            Weather.FOG
        };
        int chip = 18;
        int gap = 7;
        int pad = 12;
        var titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 13);
        var badgeFont = new Font(Font.SANS_SERIF, Font.BOLD, 11);
        g2.setFont(titleFont);
        var titleFm = g2.getFontMetrics();
        int chipsW = wx.length * chip + Math.max(0, wx.length - 1) * gap;
        int innerW = pad * 2 + chipsW;
        int innerH = pad + titleFm.getHeight() + 10 + chip + pad;
        int boxPad = 10;
        int x = getWidth() - innerW - boxPad - 6;
        int y = getHeight() - innerH - boxPad - 18;
        if (x < 16 || y < 16) {
            return;
        }
        g2.setColor(new Color(14, 18, 26, 220));
        g2.fillRoundRect(x, y, innerW, innerH, 10, 10);
        g2.setColor(new Color(70, 82, 98, 200));
        g2.drawRoundRect(x, y, innerW, innerH, 10, 10);
        g2.setFont(titleFont);
        g2.setColor(new Color(210, 218, 232));
        int titleBaseline = y + pad + titleFm.getAscent();
        g2.drawString("Weather tint key", x + pad, titleBaseline);
        int rowTop = titleBaseline + titleFm.getDescent() + 10;
        int cx = x + pad;
        g2.setFont(badgeFont);
        var badgeFm = g2.getFontMetrics();
        for (Weather w : wx) {
            Color wash = weatherWashColor(w, true);
            g2.setColor(new Color(wash.getRed(), wash.getGreen(), wash.getBlue(), 248));
            g2.fillRoundRect(cx, rowTop, chip, chip, 4, 4);
            char badge = w.badgeChar();
            if (badge != ' ') {
                g2.setColor(weatherBadgeInk(w));
                String bs = String.valueOf(badge);
                float tx = cx + (chip - badgeFm.stringWidth(bs)) / 2f;
                float ty = rowTop + (chip - badgeFm.getHeight()) / 2f + badgeFm.getAscent() - 1f;
                g2.drawString(bs, tx, ty);
            }
            cx += chip + gap;
        }
    }

    private void paintMiniMap(Graphics2D g2) {
        if (session == null || minQ > maxQ || minR > maxR) return;
        int mmW = MINIMAP_W;
        int mmH = MINIMAP_H;
        int x = getWidth() - mmW - MINIMAP_EDGE_PAD;
        int y = MINIMAP_EDGE_PAD;
        g2.setColor(new Color(16, 23, 34, 220));
        g2.fillRoundRect(x, y, mmW, mmH, 10, 10);
        int curSeat = session.currentPlayer().seat();
        var visited = session.visitedFor(curSeat);
        var visible = session.visibleFor(curSeat);
        double qRange = Math.max(1, maxQ - minQ + 1);
        double rRange = Math.max(1, maxR - minR + 1);
        for (var c : session.map().allCells()) {
            double px = x + 8 + ((c.q() - minQ) / qRange) * (mmW - 16);
            double py = y + 8 + ((c.r() - minR) / rRange) * (mmH - 16);
            Color base;
            if (!visited.contains(c)) {
                base = new Color(30, 34, 44, 210);
            } else if (visible.contains(c)) {
                base = new Color(110, 180, 230, 210);
            } else {
                base = new Color(70, 88, 112, 210);
            }
            var claim = session.claimedOwnerAt(c);
            if (claim.isPresent()) {
                boolean tint = !minimapTintOwnClaimsOnly || claim.get() == curSeat;
                if (tint) {
                    Color pc = UiTheme.PLAYER[claim.get() % UiTheme.PLAYER.length];
                    float t = visible.contains(c) ? 0.36f : 0.22f;
                    base = blend(base, pc, t);
                }
            }
            g2.setColor(base);
            g2.fillRect((int) px, (int) py, 3, 3);
        }
        for (var city : session.cities()) {
            if (!visited.contains(city.coord())) {
                continue;
            }
            double px = x + 8 + ((city.coord().q() - minQ) / qRange) * (mmW - 16);
            double py = y + 8 + ((city.coord().r() - minR) / rRange) * (mmH - 16);
            g2.setColor(UiTheme.PLAYER[city.ownerSeat() % UiTheme.PLAYER.length]);
            g2.fillRect((int) px - 1, (int) py - 1, 5, 5);
        }

        var mmClip = new RoundRectangle2D.Double(x, y, mmW, mmH, 10, 10);
        var prevClip = g2.getClip();
        g2.clip(mmClip);
        paintMiniMapViewport(g2, x, y, mmW, mmH);
        var mmVignette = new RadialGradientPaint(
                x + mmW * 0.5f, y + mmH * 0.48f, mmW * 0.78f,
                new float[] {0.32f, 1f},
                new Color[] {new Color(0, 0, 0, 0), new Color(6, 10, 18, 62)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        var oldPaintMM = g2.getPaint();
        g2.setPaint(mmVignette);
        g2.fill(mmClip);
        g2.setPaint(oldPaintMM);
        var oldStrokeMm = g2.getStroke();
        g2.setStroke(new BasicStroke(1.4f));
        g2.setColor(new Color(255, 255, 255, 62));
        g2.drawRoundRect(x + 2, y + 2, mmW - 4, mmH - 4, 8, 8);
        g2.setStroke(new BasicStroke(2.4f));
        g2.setColor(new Color(12, 18, 28, 215));
        g2.drawRoundRect(x, y, mmW, mmH, 10, 10);
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(255, 255, 255, 72));
        g2.drawLine(x + 10, y + 11, x + mmW - 14, y + mmH - 18);
        g2.setStroke(oldStrokeMm);
        g2.setClip(prevClip);
    }

    /** Camera field-of-view quad mapped onto the overview (Civ-style). */
    private void paintMiniMapViewport(Graphics2D g2, int mx, int my, int mmW, int mmH) {
        int pw = getWidth();
        int ph = getHeight();
        if (pw < 8 || ph < 8) {
            return;
        }
        double qRange = Math.max(1, maxQ - minQ + 1);
        double rRange = Math.max(1, maxR - minR + 1);
        double[][] corners = {{0, 0}, {pw, 0}, {pw, ph}, {0, ph}};
        var path = new Path2D.Double();
        for (int i = 0; i < corners.length; i++) {
            Point2D world = screenToWorld(corners[i][0], corners[i][1]);
            double fr = world.getY() / (HEX_R * 1.5);
            double fq = world.getX() / (HEX_R * Math.sqrt(3)) - fr / 2.0;
            double px = mx + 8 + (fq - minQ) / qRange * (mmW - 16);
            double py = my + 8 + (fr - minR) / rRange * (mmH - 16);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.closePath();
        var old = g2.getStroke();
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(255, 255, 255, 42));
        g2.fill(path);
        g2.setColor(new Color(255, 255, 255, 228));
        g2.draw(path);
        g2.setStroke(old);
    }

    private void paintToast(Graphics2D g2) {
        if (toastText == null || toastText.isBlank()) return;
        long now = System.currentTimeMillis();
        if (now > toastUntilMs) return;
        float t = Math.max(0f, Math.min(1f, (toastUntilMs - now) / 3200f));
        int alpha = 120 + (int) (110 * t);
        int w = Math.min(getWidth() - 50, 520);
        int x = (getWidth() - w) / 2;
        int y = 20;
        g2.setColor(new Color(18, 24, 34, alpha));
        g2.fillRoundRect(x, y, w, 30, 10, 10);
        g2.setColor(new Color(220, 230, 245, Math.min(255, alpha + 20)));
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g2.drawString(toastText, x + 12, y + 20);
    }

    private void paintTile(Graphics2D g2, HexCoord c, Terrain t,
                            double cx, double cy, boolean visible, boolean visited) {
        if (!visited) {
            // Unknown: draw very dark hex
            fillHex(g2, cx, cy, UiTheme.FOG_UNSEEN);
            drawHexOutline(g2, cx, cy, new Color(0, 0, 0, 100), 1f);
            return;
        }

        Color base = UiTheme.terrainFill(t);
        Color shade = UiTheme.terrainShade(t);
        Color hi = UiTheme.terrainHighlight(t);
        BufferedImage terrainPng = terrainImageFor(t);
        boolean usingTerrainArt = terrainPng != null;
        if (session.improvementAt(c) == TileImprovement.FARM) {
            base = blend(base, new Color(200, 225, 140), 0.38f);
            shade = blend(shade, new Color(110, 140, 75), 0.32f);
            hi = blend(hi, new Color(235, 248, 190), 0.25f);
        }

        if (!visible) {
            // Visited but currently fogged: desaturate + darken
            base = blend(base, Color.DARK_GRAY, 0.55f);
            shade = blend(shade, Color.BLACK, 0.55f);
            hi = blend(hi, Color.DARK_GRAY, 0.55f);
        }

        base = tintTerrainForElevation(base, t);
        shade = tintTerrainForElevation(shade, t);
        hi = tintTerrainForElevation(hi, t);

        if (usingTerrainArt) {
            var oldClip = g2.getClip();
            var hex = hexPath(cx, cy);
            g2.clip(hex);
            paintTerrainImageCoverInHex(g2, terrainPng, cx, cy);
            // Skip directional grade on water bitmap — combined with rim/shimmer it reads as a raised hex plate.
            if (t != Terrain.WATER) {
                paintTerrainLightGrade(g2, cx, cy, !visible, false);
            }
            paintElevationBitmapWash(g2, cx, cy, t);
            if (visible && t == Terrain.WATER && waterShimmerActive(c)) {
                paintWaterShimmer(g2, cx, cy, c);
            }
            if (!visible) {
                g2.setColor(new Color(18, 22, 30, 140));
                g2.fill(hex);
            }
            g2.setClip(oldClip);
        } else {
            float litX = (float) (cx + LIGHT_DIR_X * HEX_R * 0.98);
            float litY = (float) (cy + LIGHT_DIR_Y * HEX_R * 0.98);
            float dimX = (float) (cx - LIGHT_DIR_X * HEX_R * 0.96);
            float dimY = (float) (cy - LIGHT_DIR_Y * HEX_R * 0.96);
            var grad = new GradientPaint(litX, litY, hi, dimX, dimY, shade);
            g2.setPaint(grad);
            g2.fill(hexPath(cx, cy));

            if (t != Terrain.WATER) {
                float sheenX = (float) (cx + LIGHT_DIR_X * HEX_R * 0.74);
                float sheenY = (float) (cy + LIGHT_DIR_Y * HEX_R * 0.74);
                var inner = new GradientPaint(
                        sheenX, sheenY, new Color(255, 255, 255, 44),
                        (float) cx, (float) cy, new Color(255, 255, 255, 0));
                g2.setPaint(inner);
                g2.fill(hexPath(cx, cy));
            }
            if (visible && t == Terrain.WATER && waterShimmerActive(c)) {
                paintWaterShimmer(g2, cx, cy, c);
            }
        }

        // Tile decoration (city layer replaces clutter on its hex)
        boolean hasCity = session.cityAt(c).isPresent();
        if (visible) {
            if (!hasCity) {
                if (!usingTerrainArt && session.improvementAt(c) != TileImprovement.FARM) {
                    paintTerrainDeco(g2, t, c, cx, cy);
                }
                paintFarmTileDeco(g2, c, cx, cy);
            }
        } else {
            // Greyer outline for fogged
            drawHexOutline(g2, cx, cy, new Color(0, 0, 0, 90), 1f);
            return;
        }

        if (t == Terrain.WATER) {
            // Open water: skip fake “bevel” rim (shadow-only edges read as bright lip on sun side); faint grid only.
            drawHexOutline(g2, cx, cy, new Color(0, 18, 28, 26), 0.75f);
        } else {
            // Low alpha — strong rim reads as raised edges when only shadow sides are stroked (NW sun).
            paintHexEdgeLightRim(g2, cx, cy, 34);
            drawHexOutline(g2, cx, cy, new Color(0, 0, 0, 68), 1f);
        }
        if (!hasCity) {
            paintTileEconomyGlyphs(g2, c, cx, cy);
        }
    }

    private void paintClaimBorders(Graphics2D g2, Set<HexCoord> visible, Set<HexCoord> visited) {
        if (session == null) return;
        var old = g2.getStroke();
        g2.setStroke(new BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {6f, 5f}, 0f));
        for (HexCoord c : session.map().allCells()) {
            if (!visited.contains(c)) continue;
            var owner = session.claimedOwnerAt(c);
            if (owner.isEmpty()) continue;
            Color base = UiTheme.PLAYER[owner.get() % UiTheme.PLAYER.length];
            Color edge = visible.contains(c)
                    ? new Color(base.getRed(), base.getGreen(), base.getBlue(), 228)
                    : new Color(base.getRed(), base.getGreen(), base.getBlue(), 138);
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            for (int i = 0; i < 6; i++) {
                HexCoord n = neighborForEdge(c, i);
                var nOwner = session.map().contains(n) ? session.claimedOwnerAt(n) : Optional.empty();
                if (nOwner.equals(owner)) continue;
                var seg = hexEdgePoints(cx, cy, i);
                g2.setColor(edge);
                g2.drawLine((int) seg[0], (int) seg[1], (int) seg[2], (int) seg[3]);
                if (nOwner.isPresent() && nOwner.get() != owner.get()) {
                    g2.setColor(new Color(255, 255, 255, visible.contains(c) ? 118 : 78));
                    g2.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {2f, 4f}, 0f));
                    g2.drawLine((int) seg[0], (int) seg[1], (int) seg[2], (int) seg[3]);
                    g2.setStroke(new BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {6f, 5f}, 0f));
                }
            }
        }
        g2.setStroke(old);
    }

    /**
     * Perpendicular color ramps from shared edges into each hex (so grass↔forest softens instead of a hard mid-line).
     */
    private void paintTerrainEdgeBlends(Graphics2D g2, Set<HexCoord> visited, Set<HexCoord> visible) {
        if (session == null || !lodDetail) {
            return;
        }
        for (HexCoord c : session.map().allCells()) {
            if (!visited.contains(c)) {
                continue;
            }
            Terrain t = session.terrainEffectiveAt(c);
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            for (int i = 0; i < 6; i++) {
                HexCoord n = neighborForEdge(c, i);
                if (!session.map().contains(n) || !visited.contains(n)) {
                    continue;
                }
                if (!(c.q() < n.q() || (c.q() == n.q() && c.r() < n.r()))) {
                    continue;
                }
                Terrain tn = session.terrainEffectiveAt(n);
                if (tn == t) {
                    continue;
                }
                if (!visible.contains(c) && !visible.contains(n)) {
                    continue;
                }
                Color ca = UiTheme.terrainFill(t);
                Color cb = UiTheme.terrainFill(tn);
                Color mid = blend(ca, cb, 0.5f);
                var seg = hexEdgePoints(cx, cy, i);
                paintTerrainEdgeHalfStrip(g2, cx, cy, seg, ca, mid);
                double ncx = axialToPixelX(n);
                double ncy = axialToPixelY(n);
                int j = edgeIndexToward(n, c);
                if (j < 0) {
                    continue;
                }
                var segN = hexEdgePoints(ncx, ncy, j);
                paintTerrainEdgeHalfStrip(g2, ncx, ncy, segN, cb, mid);
            }
        }
    }

    private static int edgeIndexToward(HexCoord from, HexCoord to) {
        for (int i = 0; i < 6; i++) {
            if (neighborForEdge(from, i).equals(to)) {
                return i;
            }
        }
        return -1;
    }

    private static void paintTerrainEdgeHalfStrip(Graphics2D g2, double cx, double cy, double[] seg,
            Color innerTint, Color edgeMix) {
        double mx = (seg[0] + seg[2]) * 0.5;
        double my = (seg[1] + seg[3]) * 0.5;
        double vx = cx - mx;
        double vy = cy - my;
        double vl = Math.hypot(vx, vy);
        if (vl < 1e-4) {
            return;
        }
        vx = vx / vl * HEX_R * 0.42;
        vy = vy / vl * HEX_R * 0.42;
        double thick = HEX_R * 0.36;
        var strip = new Path2D.Double();
        strip.moveTo(seg[0], seg[1]);
        strip.lineTo(seg[2], seg[3]);
        strip.lineTo(seg[2] + vx, seg[3] + vy);
        strip.lineTo(seg[0] + vx, seg[1] + vy);
        strip.closePath();
        var hex = hexPath(cx, cy);
        var oc = g2.getClip();
        g2.clip(hex);
        var g = new GradientPaint(
                (float) mx, (float) my,
                new Color(edgeMix.getRed(), edgeMix.getGreen(), edgeMix.getBlue(), 118),
                (float) (mx + vx * (thick / (HEX_R * 0.42))),
                (float) (my + vy * (thick / (HEX_R * 0.42))),
                new Color(innerTint.getRed(), innerTint.getGreen(), innerTint.getBlue(), 0));
        g2.setPaint(g);
        g2.fill(strip);
        g2.setClip(oc);
    }

    /** Soft foam band on water hex edges that border dry land (filled strip — no stroked caps at vertices). */
    private void paintCoastFoam(Graphics2D g2, Set<HexCoord> visited, Set<HexCoord> visible) {
        if (session == null || !lodDetail) {
            return;
        }
        for (HexCoord c : session.map().allCells()) {
            if (!visited.contains(c) || !visible.contains(c)) {
                continue;
            }
            if (session.terrainEffectiveAt(c) != Terrain.WATER) {
                continue;
            }
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            var hex = hexPath(cx, cy);
            for (int i = 0; i < 6; i++) {
                HexCoord n = neighborForEdge(c, i);
                if (!session.map().contains(n) || !visited.contains(n)) {
                    continue;
                }
                if (session.terrainEffectiveAt(n) == Terrain.WATER) {
                    continue;
                }
                var seg = hexEdgePoints(cx, cy, i);
                double mx = (seg[0] + seg[2]) * 0.5;
                double my = (seg[1] + seg[3]) * 0.5;
                double vx = cx - mx;
                double vy = cy - my;
                double len = Math.hypot(vx, vy);
                if (len < 1e-3) {
                    continue;
                }
                vx = vx / len * HEX_R * 0.5;
                vy = vy / len * HEX_R * 0.5;
                double thick = HEX_R * 0.16;
                var strip = new Path2D.Double();
                strip.moveTo(seg[0], seg[1]);
                strip.lineTo(seg[2], seg[3]);
                strip.lineTo(seg[2] + vx * (thick / (HEX_R * 0.5)), seg[3] + vy * (thick / (HEX_R * 0.5)));
                strip.lineTo(seg[0] + vx * (thick / (HEX_R * 0.5)), seg[1] + vy * (thick / (HEX_R * 0.5)));
                strip.closePath();
                var oc = g2.getClip();
                g2.clip(hex);
                var foam = new GradientPaint(
                        (float) mx, (float) my, new Color(220, 246, 255, 72),
                        (float) (mx + vx * (thick / (HEX_R * 0.5))),
                        (float) (my + vy * (thick / (HEX_R * 0.5))),
                        new Color(220, 246, 255, 0));
                g2.setPaint(foam);
                g2.fill(strip);
                g2.setClip(oc);
            }
        }
    }

    /** Darker water just inside coast (reads as shallow vs deep). */
    private void paintShoreWaterDepth(Graphics2D g2, Set<HexCoord> visited, Set<HexCoord> visible) {
        if (session == null || !lodDetail) {
            return;
        }
        for (HexCoord c : session.map().allCells()) {
            if (!visited.contains(c) || !visible.contains(c)) {
                continue;
            }
            if (session.terrainEffectiveAt(c) != Terrain.WATER) {
                continue;
            }
            double cx = axialToPixelX(c);
            double cy = axialToPixelY(c);
            var hex = hexPath(cx, cy);
            for (int i = 0; i < 6; i++) {
                HexCoord n = neighborForEdge(c, i);
                if (!session.map().contains(n) || !visited.contains(n)) {
                    continue;
                }
                if (session.terrainEffectiveAt(n) == Terrain.WATER) {
                    continue;
                }
                var seg = hexEdgePoints(cx, cy, i);
                double mx = (seg[0] + seg[2]) * 0.5;
                double my = (seg[1] + seg[3]) * 0.5;
                double vx = cx - mx;
                double vy = cy - my;
                double vl = Math.hypot(vx, vy);
                if (vl < 1e-3) {
                    continue;
                }
                vx = vx / vl * HEX_R * 0.5;
                vy = vy / vl * HEX_R * 0.5;
                double thick = HEX_R * 0.42;
                var strip = new Path2D.Double();
                strip.moveTo(seg[0], seg[1]);
                strip.lineTo(seg[2], seg[3]);
                strip.lineTo(seg[2] + vx, seg[3] + vy);
                strip.lineTo(seg[0] + vx, seg[1] + vy);
                strip.closePath();
                var oc = g2.getClip();
                g2.clip(hex);
                var deep = new GradientPaint(
                        (float) mx, (float) my, new Color(5, 35, 72, 108),
                        (float) (mx + vx * (thick / (HEX_R * 0.5))),
                        (float) (my + vy * (thick / (HEX_R * 0.5))),
                        new Color(0, 55, 95, 0));
                g2.setPaint(deep);
                g2.fill(strip);
                g2.setClip(oc);
            }
        }
    }

    /** Large soft mist patches (seeded; world space). */
    private void paintRegionalFogBanks(Graphics2D g2, Set<HexCoord> visited) {
        if (session == null || !lodDetail) {
            return;
        }
        var rng = new Random(session.worldSeed() ^ 0xC10DB4B55L);
        var old = g2.getPaint();
        double spanX = Math.max(HEX_R * 4, worldMaxX - worldMinX);
        double spanY = Math.max(HEX_R * 4, worldMaxY - worldMinY);
        for (int k = 0; k < 6; k++) {
            double fx = worldMinX + rng.nextDouble() * spanX;
            double fy = worldMinY + rng.nextDouble() * spanY;
            float rad = (float) (HEX_R * (7.5 + rng.nextDouble() * 8));
            var rg = new RadialGradientPaint(
                    (float) fx, (float) fy, rad,
                    new float[] {0f, 1f},
                    new Color[] {new Color(228, 235, 245, 22), new Color(228, 235, 245, 0)},
                    MultipleGradientPaint.CycleMethod.NO_CYCLE);
            g2.setPaint(rg);
            g2.fill(new Ellipse2D.Double(fx - rad, fy - rad, rad * 2, rad * 2));
        }
        g2.setPaint(old);
    }

    /**
     * Animated wind streaks on clusters of open-terrain tiles (grass, plains, desert).
     * Clusters are seeded from {@code worldSeed} so they're stable across frames.
     * Each cluster has its own wind angle (still broadly "eastward"); streaks scroll along that angle.
     */
    private void paintWindClusters(Graphics2D g2, Set<HexCoord> visited, Set<HexCoord> visible) {
        if (session == null) {
            return;
        }
        // Determine cluster membership per cell: hash cell + seed, keep ~28% of open-terrain cells.
        long seed = session.worldSeed() ^ 0xB1E5AC1A573FL;
        var rng = new Random(seed);

        // Sample a few cluster "centres" and radii; every open-terrain cell within that radius is in the cluster.
        int NUM_CLUSTERS = 7;
        double spanX = Math.max(HEX_R * 4, worldMaxX - worldMinX);
        double spanY = Math.max(HEX_R * 4, worldMaxY - worldMinY);
        double[] clX = new double[NUM_CLUSTERS];
        double[] clY = new double[NUM_CLUSTERS];
        double[] clR = new double[NUM_CLUSTERS];
        double[] clAngle = new double[NUM_CLUSTERS]; // wind direction per cluster
        for (int k = 0; k < NUM_CLUSTERS; k++) {
            clX[k] = worldMinX + rng.nextDouble() * spanX;
            clY[k] = worldMinY + rng.nextDouble() * spanY;
            clR[k] = HEX_R * (5 + rng.nextDouble() * 6);
            // Broadly eastward (right + slightly up/down), ±40° spread
            clAngle[k] = Math.toRadians(-15 + rng.nextDouble() * 30);
        }

        var oldStroke = g2.getStroke();
        var oldClip = g2.getClip();

        for (var c : session.map().allCells()) {
            if (!visited.contains(c) || !visible.contains(c)) {
                continue;
            }
            Terrain t = session.terrainEffectiveAt(c);
            if (t != Terrain.GRASS && t != Terrain.PLAINS && t != Terrain.DESERT) {
                continue;
            }
            double px = axialToPixelX(c);
            double py = axialToPixelY(c);

            // Find which cluster this cell belongs to (first match wins)
            int clIdx = -1;
            double minDist = Double.MAX_VALUE;
            for (int k = 0; k < NUM_CLUSTERS; k++) {
                double dx = px - clX[k];
                double dy = py - clY[k];
                double dist = Math.hypot(dx, dy);
                if (dist < clR[k] && dist < minDist) {
                    minDist = dist;
                    clIdx = k;
                }
            }
            if (clIdx < 0) {
                continue;
            }

            // Per-cell stable offset so streaks don't sync across tiles
            int cellHash = c.q() * 92837111 ^ c.r() * 689287499;
            double cellOff = (cellHash & 0xFFFF) / (double) 0x10000; // [0,1)

            double angle = clAngle[clIdx];
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double edgeProj = HEX_R * 1.2; // streak spans ±this in wind direction

            // Fade at cluster edge
            double edgeFade = 1.0 - (minDist / clR[clIdx]);
            int baseA = (int) (52 * edgeFade);

            var hex = hexPath(px, py);
            g2.clip(hex);
            g2.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));

            int STREAKS = lodDetail ? 4 : 2;
            for (int s = 0; s < STREAKS; s++) {
                // Perpendicular offset lanes spread across the hex
                double laneT = (s + 0.5) / STREAKS; // [0,1)
                double perpOff = (laneT - 0.5) * HEX_R * 1.5;
                // Each lane has its own phase offset so they scroll independently
                double laneOff = (s * 0.37 + cellOff) % 1.0;
                // Scroll: streak head position along wind axis, wraps over the hex
                double scroll = ((windPhase * 0.35 + laneOff) % 1.0);
                // Length varies between ~30-65% of hex width per frame and lane
                double len = edgeProj * (0.3 + 0.35 * ((s * 0.618 + cellOff * 2) % 1.0));
                // Head position goes from -edgeProj to +edgeProj, scrolling
                double head = -edgeProj + scroll * edgeProj * 2.2;
                double tail = head - len;

                // Perpendicular direction (rotated 90°)
                double perpX = -sin;
                double perpY = cos;
                double x1 = px + cos * tail + perpX * perpOff;
                double y1 = py + sin * tail + perpY * perpOff;
                double x2 = px + cos * head + perpX * perpOff;
                double y2 = py + sin * head + perpY * perpOff;

                // Alpha: fade in at tail, bright at head
                int headA = Math.min(255, baseA);
                int tailA = Math.max(0, headA - 38);
                Color headCol = new Color(255, 255, 240, headA);
                Color tailCol = new Color(255, 255, 220, tailA);
                g2.setPaint(new GradientPaint((float) x1, (float) y1, tailCol, (float) x2, (float) y2, headCol));
                g2.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
            }
            g2.setClip(oldClip);
        }

        g2.setStroke(oldStroke);
        g2.setClip(oldClip);
    }

    /**
     * One full day/night cycle spans {@code 10 × yearsPerFullRound} in-game years (calendar + seat progress within the
     * round for smooth motion between full-round ticks).
     */
    private static double diurnalPhaseRadians(GameSession s) {
        int ypr = Math.max(1, s.yearsPerFullRound());
        double periodYears = 10.0 * ypr;
        int nPlayers = Math.max(1, s.players().size());
        double fracYears = s.chronologyOffsetYears() + ypr * (s.currentPlayerIndex() / (double) nPlayers);
        double m = ((fracYears % periodYears) + periodYears) % periodYears;
        return 2.0 * Math.PI * (m / periodYears);
    }

    /** Warm/cool screen tint synced to the in-game calendar (after vignette). */
    private void paintTimeOfDayOverlay(Graphics2D g2) {
        if (session == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        double ph = diurnalPhaseRadians(session);
        double sun = Math.max(0, Math.sin(ph));
        double twi = Math.pow(Math.cos(ph), 2);

        var old = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.88f));

        float y1 = 0;
        float y2 = h;
        var sky = new GradientPaint(
                0, y1, new Color(255, 248, 220, (int) (28 * twi + 12 * sun)),
                0, y2, new Color(25, 45, 92, (int) (52 * (1 - sun) + 18)));
        g2.setPaint(sky);
        g2.fillRect(0, 0, w, h);

        float cx = w * 0.48f;
        float cy = h * (0.38f + 0.08f * (float) sun);
        float r = Math.max(w, h) * 0.85f;
        var sunGlow = new RadialGradientPaint(
                cx, cy, r,
                new float[] {0f, 0.55f, 1f},
                new Color[] {
                        new Color(255, 235, 190, (int) (35 * sun)),
                        new Color(255, 200, 140, (int) (18 * twi)),
                        new Color(255, 200, 140, 0),
                },
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g2.setPaint(sunGlow);
        g2.fillRect(0, 0, w, h);

        g2.setComposite(old);
    }

    private void paintCombatFlash(Graphics2D g2, double cx, double cy) {
        long now = System.currentTimeMillis();
        long span = flashUntilMs - flashStartMs;
        float u = span > 0 ? (now - flashStartMs) / (float) span : 1f;
        u = Math.max(0f, Math.min(1f, u));
        float pulse = (float) (0.55 + 0.45 * Math.sin(u * Math.PI));
        int coreA = (int) (155 * (1 - u * 0.92) * pulse);
        fillHex(g2, cx, cy, new Color(255, 65, 65, Math.max(0, Math.min(220, coreA))));
        float ringW = (float) (2.2 + 3.8 * (1 - u));
        drawHexOutline(g2, cx, cy, new Color(255, 210, 140, (int) (215 * (1 - u * 0.88))), ringW);
        if (u < 0.88f) {
            drawHexOutline(g2, cx, cy, new Color(255, 255, 255, (int) (125 * (1 - u))), 4.2f * (1.05f - u));
        }
        var old = g2.getStroke();
        int sparks = 10;
        for (int i = 0; i < sparks; i++) {
            double ang = u * Math.PI * 3 + i * 0.7;
            double len = HEX_R * (0.35 + 0.55 * (1 - u)) * (0.4 + 0.1 * (i % 3));
            double x2 = cx + Math.cos(ang) * len;
            double y2 = cy + Math.sin(ang) * len;
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(255, 240, 200, (int) (220 * (1 - u))));
            g2.drawLine((int) cx, (int) cy, (int) x2, (int) y2);
        }
        g2.setStroke(old);
    }

    private static int clampWeight(int value) {
        return Math.max(0, Math.min(9, value));
    }

    private static HexCoord neighborForEdge(HexCoord c, int edgeIdx) {
        int[][] dqdr = {{0, -1}, {1, -1}, {1, 0}, {0, 1}, {-1, 1}, {-1, 0}};
        int i = Math.floorMod(edgeIdx, 6);
        return new HexCoord(c.q() + dqdr[i][0], c.r() + dqdr[i][1]);
    }

    private static double[] hexEdgePoints(double cx, double cy, int edgeIdx) {
        int i0 = Math.floorMod(edgeIdx, 6);
        int i1 = (i0 + 1) % 6;
        double a0 = -Math.PI / 2 + i0 * Math.PI / 3;
        double a1 = -Math.PI / 2 + i1 * Math.PI / 3;
        double x0 = cx + HEX_R * Math.cos(a0);
        double y0 = cy + HEX_R * Math.sin(a0);
        double x1 = cx + HEX_R * Math.cos(a1);
        double y1 = cy + HEX_R * Math.sin(a1);
        return new double[] {x0, y0, x1, y1};
    }

    private void paintTileEconomyGlyphs(Graphics2D g2, HexCoord c, double cx, double cy) {
        TileImprovement imp = session.improvementAt(c);
        if (imp == TileImprovement.FARM) {
            if (farmPng == null && farmSprite == null) {
                paintFarmCropsGlyph(g2, cx, cy);
            }
        } else if (imp == TileImprovement.MINE) {
            if (minePng != null) {
                paintImage(g2, minePng, cx + HEX_R * 0.18, cy + HEX_R * 0.08, 0.48);
            } else if (mineSprite != null) {
                paintSprite(g2, mineSprite, cx + HEX_R * 0.18, cy + HEX_R * 0.08, 0.62,
                        new Color(210, 215, 235, 245));
            } else {
                g2.setColor(new Color(200, 200, 220, 240));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
                g2.drawString("Mi", (float) (cx - HEX_R * 0.62), (float) (cy + HEX_R * 0.5));
            }
        }
        int cult = session.cultivationAt(c);
        if (cult > 0 && imp == TileImprovement.NONE) {
            g2.setColor(new Color(120, 200, 255, 200));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            g2.drawString("+" + cult, (float) (cx + HEX_R * 0.25), (float) (cy + HEX_R * 0.45));
        }
    }

    private void paintWildAnimal(Graphics2D g2, WildAnimal a) {
        double cx = axialToPixelX(a.coord());
        double cy = axialToPixelY(a.coord());
        paintGroundEllipseShadow(g2, cx, cy, HEX_R * 0.36, HEX_R * 0.13,
                HEX_R * 0.08, HEX_R * 0.10, 52);
        paintGroundEllipseShadow(g2, cx, cy, HEX_R * 0.42, HEX_R * 0.16,
                HEX_R * 0.05, HEX_R * 0.07, 28);
        if (wildlifePng != null) {
            paintImage(g2, wildlifePng, cx, cy, 0.62);
        } else if (wildlifeSprite != null) {
            paintSprite(g2, wildlifeSprite, cx, cy, 0.92, new Color(255, 248, 238, 240));
        } else {
            g2.setColor(new Color(200, 60, 40, 220));
            var disk = new Ellipse2D.Double(cx - 9, cy - 8, 18, 16);
            g2.fill(disk);
            drawHexOutline(g2, cx, cy, new Color(90, 20, 20, 200), 1.6f);
            g2.setColor(new Color(255, 250, 240, 235));
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            String ch = String.valueOf(a.kind().glyph());
            var fm = g2.getFontMetrics();
            g2.drawString(ch, (float) (cx - fm.charWidth(ch.charAt(0)) / 2.0),
                    (float) (cy + fm.getAscent() / 2.0 - 3));
        }

        // Always overlay species letter so wildlife type is readable even with custom art.
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        String ch = String.valueOf(a.kind().glyph());
        var fm = g2.getFontMetrics();
        float tx = (float) (cx - fm.charWidth(ch.charAt(0)) / 2.0);
        float ty = (float) (cy + fm.getAscent() / 2.0 - 3);
        g2.setColor(new Color(15, 12, 8, 210));
        g2.drawString(ch, tx + 1, ty + 1);
        g2.setColor(new Color(255, 250, 240, 238));
        g2.drawString(ch, tx, ty);
    }

    private void paintTerrainDeco(Graphics2D g2, Terrain t, HexCoord cell, double cx, double cy) {
        double wx = Math.sin(decorWindPhase * 1.08 + cell.q() * 0.71 + cell.r() * 0.53) * 1.4;
        double wy = Math.cos(decorWindPhase * 0.9 + cell.r() * 0.62 - cell.q() * 0.29) * 1.15;
        switch (t) {
            case FOREST -> {
                paintTree(g2, cx - HEX_R * 0.32 + wx * 0.9, cy + HEX_R * 0.14 + wy * 0.85);
                paintTree(g2, cx + HEX_R * 0.10 + wx * 0.55, cy - HEX_R * 0.18 + wy * 0.7);
                paintTree(g2, cx + HEX_R * 0.30 - wx * 0.35, cy + HEX_R * 0.22 + wy * 0.6);
            }
            case HILL -> {
                g2.setColor(new Color(0x6a_4f_30));
                g2.setStroke(new BasicStroke(2.0f));
                var arc = new java.awt.geom.Arc2D.Double(
                        cx - HEX_R * 0.45 + wx * 0.25, cy - HEX_R * 0.05 + wy * 0.2,
                        HEX_R * 0.9, HEX_R * 0.5,
                        20, 140, java.awt.geom.Arc2D.OPEN);
                g2.draw(arc);
                var arc2 = new java.awt.geom.Arc2D.Double(
                        cx - HEX_R * 0.20 - wx * 0.15, cy - HEX_R * 0.30 + wy * 0.25,
                        HEX_R * 0.7, HEX_R * 0.5,
                        20, 140, java.awt.geom.Arc2D.OPEN);
                g2.draw(arc2);
            }
            case MOUNTAIN -> {
                var p = new Path2D.Double();
                p.moveTo(cx - HEX_R * 0.55, cy + HEX_R * 0.40);
                p.lineTo(cx - HEX_R * 0.10, cy - HEX_R * 0.40);
                p.lineTo(cx + HEX_R * 0.20, cy + HEX_R * 0.10);
                p.lineTo(cx + HEX_R * 0.40, cy - HEX_R * 0.20);
                p.lineTo(cx + HEX_R * 0.65, cy + HEX_R * 0.40);
                p.closePath();
                g2.setColor(new Color(0x44_44_4a));
                g2.fill(p);
                // Snow caps
                var snow = new Path2D.Double();
                snow.moveTo(cx - HEX_R * 0.20, cy - HEX_R * 0.20);
                snow.lineTo(cx - HEX_R * 0.10, cy - HEX_R * 0.40);
                snow.lineTo(cx + HEX_R * 0.00, cy - HEX_R * 0.20);
                snow.closePath();
                g2.setColor(Color.WHITE);
                g2.fill(snow);
            }
            case DESERT -> {
                g2.setColor(new Color(0xb6_94_4d));
                for (int i = 0; i < 7; i++) {
                    double x = cx + (i - 3) * HEX_R * 0.18 + (i % 2) * 3 + wx * 0.35;
                    double y = cy + ((i % 3) - 1) * HEX_R * 0.18 + wy * 0.35;
                    g2.fillOval((int) x, (int) y, 2, 2);
                }
            }
            case PLAINS -> {
                g2.setColor(new Color(0x9a_a5_4a));
                g2.setStroke(new BasicStroke(1.0f));
                for (int i = 0; i < 4; i++) {
                    double y = cy - HEX_R * 0.25 + i * HEX_R * 0.15 + wy * 0.4 + i * wx * 0.05;
                    g2.drawLine((int) (cx - HEX_R * 0.45 + wx * 0.3), (int) y,
                            (int) (cx + HEX_R * 0.45 + wx * 0.25), (int) y);
                }
            }
            case GRASS -> {
                g2.setColor(new Color(0x34_77_3a));
                for (int i = 0; i < 5; i++) {
                    double x = cx - HEX_R * 0.30 + i * HEX_R * 0.16 + wx * 0.25;
                    double y = cy + HEX_R * 0.05 + ((i % 2) * HEX_R * 0.10) + wy * 0.3;
                    g2.fillOval((int) x, (int) y, 2, 2);
                }
            }
            case WATER -> {
                g2.setColor(new Color(255, 255, 255, 50));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawArc((int) (cx - HEX_R * 0.30 + wx * 0.2), (int) (cy - HEX_R * 0.05 + wy * 0.15),
                        (int) (HEX_R * 0.6), (int) (HEX_R * 0.2), 0, 180);
                g2.drawArc((int) (cx - HEX_R * 0.10 - wx * 0.12), (int) (cy + HEX_R * 0.20 + wy * 0.12),
                        (int) (HEX_R * 0.5), (int) (HEX_R * 0.18), 0, 180);
            }
        }
    }

    private void paintFarmTileDeco(Graphics2D g2, HexCoord c, double cx, double cy) {
        if (session.improvementAt(c) != TileImprovement.FARM) {
            return;
        }
        if (farmPng != null) {
            paintImage(g2, farmPng, cx, cy - HEX_R * 0.05, 1.08);
        } else if (farmSprite != null) {
            paintSprite(g2, farmSprite, cx, cy - HEX_R * 0.05, 1.38, new Color(235, 250, 210, 245));
        }
    }

    private static void paintFarmCropsGlyph(Graphics2D g2, double cx, double cy) {
        g2.setColor(new Color(52, 118, 62, 210));
        g2.setStroke(new BasicStroke(1.15f));
        for (int row = 0; row < 3; row++) {
            double y = cy + HEX_R * 0.06 + row * HEX_R * 0.13;
            g2.drawLine((int) (cx - HEX_R * 0.38), (int) y, (int) (cx + HEX_R * 0.38), (int) y);
        }
    }

    private static void paintTree(Graphics2D g2, double cx, double cy) {
        var trunk = new java.awt.geom.Rectangle2D.Double(cx - 1.2, cy + 2, 2.4, 5);
        g2.setColor(new Color(0x3a_25_18));
        g2.fill(trunk);
        var crown = new Path2D.Double();
        crown.moveTo(cx - 6, cy + 3);
        crown.lineTo(cx, cy - 8);
        crown.lineTo(cx + 6, cy + 3);
        crown.closePath();
        g2.setColor(new Color(0x1f_55_2c));
        g2.fill(crown);
        g2.setColor(new Color(0, 0, 0, 90));
        g2.draw(crown);
    }

    private static void fillHex(Graphics2D g2, double cx, double cy, Color fill) {
        g2.setColor(fill);
        g2.fill(hexPath(cx, cy));
    }

    private static void drawHexOutline(Graphics2D g2, double cx, double cy, Color stroke, float width) {
        var oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(width));
        g2.setColor(stroke);
        g2.draw(hexPath(cx, cy));
        g2.setStroke(oldStroke);
    }

    private static Path2D.Double hexPath(double cx, double cy) {
        var p = new Path2D.Double();
        for (int i = 0; i < 6; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 3;
            double x = cx + HEX_R * Math.cos(a);
            double y = cy + HEX_R * Math.sin(a);
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    /** Unit vector from hex center toward edge midpoint (pointy-top); indices match {@link #hexEdgePoints}. */
    private static double[] hexEdgeOutwardNormal(int edgeIdx) {
        double theta = -Math.PI / 2 + (Math.floorMod(edgeIdx, 6) + 0.5) * Math.PI / 3;
        return new double[] { Math.cos(theta), Math.sin(theta) };
    }

    /** Darken edges that face away from {@link #LIGHT_DIR_X}/{@link #LIGHT_DIR_Y} (SE-ish). */
    private static void paintHexEdgeLightRim(Graphics2D g2, double cx, double cy, int alphaCap) {
        var oldStroke = g2.getStroke();
        for (int i = 0; i < 6; i++) {
            double[] n = hexEdgeOutwardNormal(i);
            double facingSun = n[0] * LIGHT_DIR_X + n[1] * LIGHT_DIR_Y;
            double shadowT = (1.0 - facingSun) * 0.5;
            if (shadowT < 0.035) {
                continue;
            }
            int a = (int) Math.round(alphaCap * (0.18 + 0.82 * shadowT));
            if (a < 8) {
                continue;
            }
            float w = 1.05f + 2.55f * (float) shadowT;
            g2.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0, 0, 0, Math.min(255, a)));
            double[] seg = hexEdgePoints(cx, cy, i);
            g2.drawLine((int) seg[0], (int) seg[1], (int) seg[2], (int) seg[3]);
        }
        g2.setStroke(oldStroke);
    }

    /**
     * Symmetric edge darkening on terrain bitmaps — avoids a fake “lit lip” on the top hex edges from NW→SE
     * grading (stacked with rim + weather reads as walls on some sides only).
     */
    private static void paintTerrainLightGrade(Graphics2D g2, double cx, double cy, boolean fogged, boolean waterSurface) {
        var hex = hexPath(cx, cy);
        var oldClip = g2.getClip();
        g2.clip(hex);
        int edgeAlpha = waterSurface
                ? (fogged ? 34 : 12)
                : (fogged ? 44 : 28);
        float r = (float) (HEX_R * 1.06);
        var rg = new RadialGradientPaint(
                (float) cx, (float) cy, r,
                new float[] {0f, 0.62f, 1f},
                new Color[] {
                        new Color(255, 255, 255, 0),
                        new Color(255, 255, 255, 0),
                        new Color(12, 16, 22, edgeAlpha),
                },
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g2.setPaint(rg);
        g2.fill(hex);
        g2.setClip(oldClip);
    }

    /** Ground contact shadow under disks/sprites; offset toward +x/+y (light from NW). */
    private static void paintGroundEllipseShadow(Graphics2D g2, double cx, double cy,
            double halfW, double halfH, double ox, double oy, int alpha) {
        g2.setColor(new Color(0, 0, 0, alpha));
        g2.fill(new Ellipse2D.Double(cx - halfW + ox, cy - halfH + oy, 2 * halfW, 2 * halfH));
    }

    /** Soft directional tint clipped to a hex (matches fake NW sun). */
    private static void fillHexDirectionalTint(Graphics2D g2, double cx, double cy, Color lit, Color shade) {
        var hex = hexPath(cx, cy);
        var oc = g2.getClip();
        g2.clip(hex);
        float lx = (float) (cx + LIGHT_DIR_X * HEX_R * 0.84);
        float ly = (float) (cy + LIGHT_DIR_Y * HEX_R * 0.84);
        float sx = (float) (cx - LIGHT_DIR_X * HEX_R * 0.80);
        float sy = (float) (cy - LIGHT_DIR_Y * HEX_R * 0.80);
        g2.setPaint(new GradientPaint(lx, ly, lit, sx, sy, shade));
        g2.fill(hex);
        g2.setClip(oc);
    }

    /** ~[0, 2π) phase offset from hex coords so tiles are not in sync. */
    private static double waterTilePhaseOffset(HexCoord c) {
        int mix = c.q() * 92837111 ^ c.r() * 689287499 ^ (c.q() - c.r()) * 314159265;
        return (mix & 0xFFFFFF) / (double) 0x1000000 * (Math.PI * 2);
    }

    /** Stable subset of water tiles get animated shimmer; reduces per-frame paint cost. */
    private static boolean waterShimmerActive(HexCoord c) {
        int mix = c.q() * 92837111 ^ c.r() * 689287499 ^ (c.q() - c.r()) * 314159265;
        return Math.floorMod(mix, 100) < SHIMMER_WATER_PERCENT;
    }

    /**
     * Three independent “current” systems: each uses a different time scale and wave mix so adjacent water
     * never looks like one sheet moving in unison.
     */
    private static int waterShimmerSystem(HexCoord c) {
        return Math.floorMod(c.q() * 31 + c.r() * 47 + c.q() * c.r() * 13, 3);
    }

    /**
     * Animated water ripples — arcs scroll horizontally per-hex; no directional (NW-biased) fills.
     * CAP_BUTT avoids round blobs at segment ends; arcs are clipped to the hex anyway.
     */
    private void paintWaterShimmer(Graphics2D g2, double cx, double cy, HexCoord cell) {
        var hex = hexPath(cx, cy);
        var oc = g2.getClip();
        g2.clip(hex);
        int sys = waterShimmerSystem(cell);
        double seed = waterTilePhaseOffset(cell);
        double t = waterShimmerPhase;
        double stretch = switch (sys) {
            case 0 -> 1.0;
            case 1 -> 1.18;
            default -> 0.84;
        };
        double ph = t * stretch + seed;
        double tAlt = t * (0.78 + sys * 0.07) + seed * 1.71 + sys * 0.55;

        // Soft symmetric base pulse (radial, no direction)
        double pulse = 0.5 + 0.5 * Math.sin(tAlt * 1.6 + seed);
        int rimA = (int) (8 + 20 * pulse);
        var basePulse = new RadialGradientPaint(
                (float) cx, (float) cy, (float) (HEX_R * 1.02),
                new float[] {0f, 0.55f, 1f},
                new Color[] {
                        new Color(195, 238, 252, Math.min(255, rimA)),
                        new Color(150, 215, 240, Math.min(255, rimA / 2 + 4)),
                        new Color(0, 55, 90, 0),
                },
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        g2.setPaint(basePulse);
        g2.fill(hex);

        // Scrolling arcs (horizontal sweep, clipped inside hex, CAP_BUTT so no round end-caps)
        var oldStroke = g2.getStroke();
        double arcSpeed = 0.72 + sys * 0.11;
        int bandCount = lodDetail ? (sys == 1 ? 4 : 3) : 2;
        for (int band = 0; band < bandCount; band++) {
            double shift = band * 2.07 + ph * arcSpeed + seed * (0.15 + band * 0.05);
            // Vertical spread across the hex; horizontal position scrolls with phase
            double xOff = HEX_R * 0.22 * Math.sin(shift * 0.7 + band * 1.3 + seed);
            double yOff = HEX_R * (-0.38 + 0.28 * band / Math.max(1, bandCount - 1) + 0.12 * Math.sin(ph * 1.28 + band * 0.7 + seed));
            double arcX = cx - HEX_R * 1.32 + xOff;
            double arcY = cy + yOff;
            double arcW = HEX_R * (2.64 + sys * 0.04);
            double arcH = HEX_R * (0.62 + (sys == 1 ? 0.06 : 0));
            double start = 160 + 22 * Math.sin(shift + sys * 0.4);
            double extent = 104 + 28 * Math.cos(ph * 1.18 + band * 0.9 + seed);
            var arc = new java.awt.geom.Arc2D.Double(arcX, arcY, arcW, arcH, start, extent, java.awt.geom.Arc2D.OPEN);
            int sa = (int) (28 + 36 * (0.5 + 0.5 * Math.sin(tAlt * 2.15 + band + seed)));
            g2.setStroke(new BasicStroke(2.0f + band * 0.25f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(210, 242, 255, Math.min(255, sa)));
            g2.draw(arc);
        }
        g2.setStroke(oldStroke);
        g2.setClip(oc);
    }

    /** Soft tint on the hex under the selected unit. */
    private static void paintHexFootprintGlow(Graphics2D g2, double cx, double cy) {
        var hex = hexPath(cx, cy);
        var oc = g2.getClip();
        g2.clip(hex);
        var wash = new GradientPaint(
                (float) (cx + LIGHT_DIR_X * HEX_R * 0.88),
                (float) (cy + LIGHT_DIR_Y * HEX_R * 0.88),
                new Color(110, 215, 255, 62),
                (float) (cx - LIGHT_DIR_X * HEX_R * 0.72),
                (float) (cy - LIGHT_DIR_Y * HEX_R * 0.72),
                new Color(255, 248, 210, 22));
        g2.setPaint(wash);
        g2.fill(hex);
        g2.setClip(oc);
    }

    private static void paintHexHoverHighlight(Graphics2D g2, double cx, double cy, boolean fullyKnown) {
        var hex = hexPath(cx, cy);
        var oc = g2.getClip();
        g2.clip(hex);
        int lit = fullyKnown ? 52 : 34;
        float hx = (float) (cx + LIGHT_DIR_X * HEX_R * 0.94);
        float hy = (float) (cy + LIGHT_DIR_Y * HEX_R * 0.94);
        float sx = (float) (cx - LIGHT_DIR_X * HEX_R * 0.58);
        float sy = (float) (cy - LIGHT_DIR_Y * HEX_R * 0.58);
        g2.setPaint(new GradientPaint(hx, hy, new Color(255, 255, 255, lit),
                sx, sy, new Color(170, 215, 255, fullyKnown ? 14 : 10)));
        g2.fill(hex);
        g2.setClip(oc);
        int edge = fullyKnown ? 175 : 112;
        drawHexOutline(g2, cx, cy, new Color(205, 235, 255, edge), 2.65f);
        drawHexOutline(g2, cx, cy, new Color(255, 255, 255, Math.min(255, edge + 28)), 1.2f);
    }

    /** Post-pass in screen space: gentle edge darkening so the map reads as a focal plane. */
    private void paintViewportVignette(Graphics2D g2) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w * 0.5f;
        float cy = h * 0.44f;
        float r = Math.max(w, h) * 0.95f;
        var vignette = new RadialGradientPaint(
                cx, cy, r,
                new float[] {0.38f, 1f},
                new Color[] {new Color(0, 0, 0, 0), new Color(6, 12, 22, 82)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
        var old = g2.getPaint();
        g2.setPaint(vignette);
        g2.fillRect(0, 0, w, h);
        g2.setPaint(old);
    }

    private void paintUnit(Graphics2D g2, Unit u) {
        double cx = axialToPixelX(u.coord());
        double cy = axialToPixelY(u.coord());
        if (session.cityAt(u.coord()).isPresent()) {
            cy += HEX_R * 0.22;
        }
        Color base = UiTheme.PLAYER[u.ownerSeat() % UiTheme.PLAYER.length];
        Color rim = base.darker();

        boolean exhausted = u.movesRemaining() == 0
                && u.ownerSeat() == session.currentPlayer().seat();
        if (exhausted) {
            base = blend(base, Color.LIGHT_GRAY, 0.45f);
            rim = blend(rim, Color.GRAY, 0.45f);
        }

        boolean pngUnit = switch (u.kind()) {
            case SCOUT -> scoutPng != null;
            case SETTLER -> settlerPng != null;
            case WARRIOR -> warriorPng != null;
            case FARMER -> farmerPng != null;
            case BUILDER -> builderPng != null;
			case HUNTING_PARTY -> false;
        };
        if (!pngUnit) {
            double shOx = HEX_R * 0.11;
            double shOy = HEX_R * 0.13;
            paintGroundEllipseShadow(g2, cx, cy, HEX_R * 0.46, HEX_R * 0.17, shOx, shOy, 56);
            paintGroundEllipseShadow(g2, cx, cy, HEX_R * 0.52, HEX_R * 0.20, shOx * 0.65, shOy * 0.65, 30);
        }

        double r = HEX_R * 0.42;
        // Uploaded/library unit art should fully replace the old faction disk/glyph badge.
        if (!usesLibraryUnitArt(u.kind())) {
            var ring = new Ellipse2D.Double(cx - r - 2.4, cy - r - 2.4, 2 * r + 4.8, 2 * r + 4.8);
            var disk = new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
            g2.setColor(rim);
            g2.fill(ring);
            g2.setColor(base);
            g2.fill(disk);
            var oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(1.15f));
            g2.setColor(new Color(255, 255, 255, 105));
            g2.draw(new Ellipse2D.Double(cx - r + 2.0, cy - r + 2.0, 2 * r - 4.0, 2 * r - 4.0));
            g2.setStroke(oldStroke);
        }

        paintUnitSymbol(g2, u, cx, cy);

        // HP bar
        if (u.hp() < u.maxHp()) {
            double bw = HEX_R * 0.95;
            double bh = 4;
            double bx = cx - bw / 2;
            double by = cy + r + 4;
            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect((int) bx - 1, (int) by - 1, (int) bw + 2, (int) bh + 2, 4, 4);
            double frac = u.hp() / (double) u.maxHp();
            g2.setColor(hpColor(frac));
            g2.fillRoundRect((int) bx, (int) by, (int) (bw * frac), (int) bh, 3, 3);
            int pulse = (int) (95 + 55 * (0.5 + 0.5 * Math.sin(uiPulsePhase * 1.5)));
            var oldHp = g2.getStroke();
            g2.setStroke(new BasicStroke(1.1f));
            g2.setColor(new Color(255, 120, 100, pulse));
            g2.drawRoundRect((int) bx - 1, (int) by - 1, (int) bw + 2, (int) bh + 2, 4, 4);
            g2.setStroke(oldHp);
        }

        if (!session.plannedRouteFor(u.id()).isEmpty()) {
            double bx = cx + r * 0.45;
            double by = cy - r * 0.55;
            g2.setColor(new Color(35, 200, 255, 230));
            g2.fillRoundRect((int) bx - 8, (int) by - 6, 14, 12, 5, 5);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            g2.drawString("➤", (float) bx - 4, (float) by + 4);
        }
    }

    private void paintCity(Graphics2D g2, City c, boolean visible) {
        double cx = axialToPixelX(c.coord());
        double cy = axialToPixelY(c.coord());
        Color base = UiTheme.PLAYER[c.ownerSeat() % UiTheme.PLAYER.length];
        if (!visible) {
            base = blend(base, Color.GRAY, 0.5f);
        }

        paintGroundEllipseShadow(g2, cx, cy, HEX_R * 0.74, HEX_R * 0.35,
                HEX_R * 0.12, HEX_R * 0.14, 46);
        paintGroundEllipseShadow(g2, cx, cy, HEX_R * 0.82, HEX_R * 0.39,
                HEX_R * 0.07, HEX_R * 0.09, 28);

        var hex = hexPath(cx, cy);
        var oldClip = g2.getClip();
        g2.clip(hex);

        g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 72));
        g2.fill(hex);

        g2.setColor(new Color(255, 255, 255, 210));
        if (cityPng != null) {
            paintImageCoverInHex(g2, cityPng, cx, cy);
        } else if (citySprite != null) {
            paintSpriteCoverInHex(g2, citySprite, cx, cy, new Color(255, 255, 255, 220));
        } else {
            paintCityFallbackWholeTile(g2, cx, cy);
        }

        g2.setClip(oldClip);

        Color rim = blend(base.darker(), Color.BLACK, 0.35f);
        drawHexOutline(g2, cx, cy, rim, 2.2f);
        int popTier = cityPopulationTier(c.population());
        if (popTier >= 2) {
            drawHexOutline(g2, cx, cy, new Color(255, 218, 150, popTier >= 3 ? 200 : 155), 1.55f);
        }
        if (popTier >= 3) {
            drawHexOutline(g2, cx, cy, new Color(255, 248, 210, 175), 0.95f);
        }

        paintCityBanner(g2, c, cx, cy, base, visible);
    }

    /** Visual city scale: 1 village / 2 town / 3 metropolis (banner + rim echo this). */
    private static int cityPopulationTier(int population) {
        if (population >= 9) {
            return 3;
        }
        if (population >= 5) {
            return 2;
        }
        return 1;
    }

    /** Civ-style floating banner: population badge, name, optional build icon, HP strip. */
    private void paintCityBanner(Graphics2D g2, City c, double cx, double cy, Color playerTint, boolean visible) {
        Color ink = visible ? new Color(248, 250, 255) : new Color(200, 202, 210);
        var bannerBg = new Color(18, 22, 30, 238);
        String popStr = Integer.toString(c.population());
        String name = c.name().toUpperCase(Locale.ROOT);
        var nameFont = new Font(Font.SANS_SERIF, Font.BOLD, 12);
        var popFont = new Font(Font.SANS_SERIF, Font.BOLD, 11);
        g2.setFont(nameFont);
        var nfm = g2.getFontMetrics();
        int nameW = nfm.stringWidth(name);
        int hBar = 4;
        int pad = 7;
        int popD = 24;
        boolean hasBuild = c.currentBuild() != null;
        int tierBanner = cityPopulationTier(c.population());
        int tierExtra = tierBanner >= 3 ? 22 : (tierBanner >= 2 ? 14 : 0);
        int buildSlot = hasBuild ? 20 : 0;
        int rowH = Math.max(popD + 2, nfm.getHeight() + 6);
        int totalW = pad + popD + pad + nameW + pad + buildSlot + tierExtra + pad;
        int bx = (int) Math.round(cx - totalW / 2.0);
        int by = (int) Math.round(cy - HEX_R * 1.42 - rowH - hBar - 6);

        g2.setColor(new Color(0, 0, 0, 38));
        g2.fillRoundRect(bx + 6, by + 10, totalW, rowH + hBar + 6, 12, 12);
        g2.setColor(new Color(0, 0, 0, 92));
        g2.fillRoundRect(bx + 2, by + 4, totalW, rowH + hBar + 6, 10, 10);
        g2.setColor(bannerBg);
        g2.fillRoundRect(bx, by, totalW, rowH + hBar + 6, 10, 10);
        g2.setColor(new Color(playerTint.getRed(), playerTint.getGreen(), playerTint.getBlue(), 210));
        var oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(1.6f));
        g2.drawRoundRect(bx, by, totalW, rowH + hBar + 6, 10, 10);
        g2.setStroke(oldStroke);

        int pcx = bx + pad + popD / 2;
        int pcy = by + rowH / 2;
        g2.setColor(new Color(0, 0, 0, 200));
        g2.fillOval(pcx - popD / 2, pcy - popD / 2, popD, popD);
        g2.setColor(ink);
        g2.setFont(popFont);
        var pfm = g2.getFontMetrics();
        g2.drawString(popStr, pcx - pfm.stringWidth(popStr) / 2, pcy + pfm.getAscent() / 2 - 1);

        g2.setFont(nameFont);
        int nameX = bx + pad + popD + pad;
        int nameY = by + (rowH + nfm.getAscent() - nfm.getDescent()) / 2 - 1;
        g2.drawString(name, nameX, nameY);

        int tierStarX = nameX + nameW + 4;
        if (tierBanner >= 3) {
            g2.setColor(new Color(255, 220, 140, 248));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g2.drawString("\u2605", tierStarX, nameY);
            tierStarX += 14;
        } else if (tierBanner >= 2) {
            g2.setColor(new Color(210, 225, 245, 215));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g2.drawString("\u2022", tierStarX, nameY);
            tierStarX += 10;
        }
        if (hasBuild) {
            g2.setColor(new Color(255, 214, 120, 240));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            g2.drawString("\u2692", tierStarX + 3, nameY);
        }

        var y = session.cityYield(c);
        int surplus = y.food() - (1 + c.population());
        String growth = surplus <= 0
                ? "stagnant"
                : ((Math.max(0, c.growthThreshold() - c.foodStored()) + surplus - 1) / surplus) + "t grow";
        g2.setColor(new Color(188, 198, 212, 220));
        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g2.drawString(growth, bx + 8, by + rowH + hBar + 16);

        int barY = by + rowH + 3;
        int barX = bx + 5;
        int barW = totalW - 10;
        g2.setColor(new Color(0, 0, 0, 175));
        g2.fillRoundRect(barX, barY, barW, hBar + 2, 4, 4);
        double frac = c.hp() / (double) c.maxHp();
        g2.setColor(hpColor(frac));
        g2.fillRoundRect(barX + 1, barY + 1, Math.max(0, (int) Math.round((barW - 2) * frac)), hBar, 3, 3);
    }

    private void paintUnitSelectionRing(Graphics2D g2, Unit u) {
        double cx = axialToPixelX(u.coord());
        double cy = axialToPixelY(u.coord());
        if (session.cityAt(u.coord()).isPresent()) {
            cy += HEX_R * 0.22;
        }
        double rx = HEX_R * 0.58;
        double ry = HEX_R * 0.30;
        double fy = cy + HEX_R * 0.12;
        double pulse = 0.82 + 0.18 * Math.sin(uiPulsePhase);
        boolean injured = u.hp() < u.maxHp();
        if (injured) {
            pulse = 0.72 + 0.28 * Math.sin(uiPulsePhase * 1.65);
        }
        int rimA = (int) (72 + 115 * pulse);
        int coreA = (int) (210 + 35 * pulse);
        var old = g2.getStroke();
        g2.setStroke(new BasicStroke((float) (2.75 + (injured ? 0.85 : 0.35) * pulse),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(255, 255, 255, Math.min(255, rimA)));
        g2.draw(new Ellipse2D.Double(cx - rx - 1.2, fy - ry - 0.8, 2 * (rx + 1.2), 2 * (ry + 0.8)));
        var ringColor = injured
                ? new Color(255, 72, 52, Math.min(255, coreA))
                : new Color(225, 52, 40, Math.min(255, coreA));
        g2.setColor(ringColor);
        g2.draw(new Ellipse2D.Double(cx - rx, fy - ry, 2 * rx, 2 * ry));
        g2.setStroke(old);
    }

    /** Scale image to cover the hex (pointy-top, circumradius {@link #HEX_R}). */
    private static void paintImageCoverInHex(Graphics2D g2, BufferedImage img, double cx, double cy) {
        if (img == null) return;
        double hexW = Math.sqrt(3) * HEX_R;
        double hexH = 2 * HEX_R;
        double scale = Math.max(hexW / img.getWidth(), hexH / img.getHeight()) * 1.03;
        int w = (int) Math.round(img.getWidth() * scale);
        int h = (int) Math.round(img.getHeight() * scale);
        int x = (int) Math.round(cx - w / 2.0);
        int y = (int) Math.round(cy - h / 2.0);
        g2.drawImage(img, x, y, w, h, null);
    }

    /**
     * Terrain textures coming from image generators often keep visual weight a bit high in-frame. Bias slightly down
     * and overscan more than units/city so hex bottoms do not show a visible gap.
     */
    private static void paintTerrainImageCoverInHex(Graphics2D g2, BufferedImage img, double cx, double cy) {
        if (img == null) return;
        double hexW = Math.sqrt(3) * HEX_R;
        double hexH = 2 * HEX_R;
        double scale = Math.max(hexW / img.getWidth(), hexH / img.getHeight()) * 1.12;
        int w = (int) Math.round(img.getWidth() * scale);
        int h = (int) Math.round(img.getHeight() * scale);
        int x = (int) Math.round(cx - w / 2.0);
        int y = (int) Math.round(cy - h / 2.0 + HEX_R * 0.10);
        g2.drawImage(img, x, y, w, h, null);
    }

    private static void paintSpriteCoverInHex(Graphics2D g2, PixelSprite sprite, double cx, double cy, Color color) {
        if (sprite == null) return;
        double hexW = Math.sqrt(3) * HEX_R;
        double hexH = 2 * HEX_R;
        double pixel = Math.max(hexW / sprite.w, hexH / sprite.h) * 1.03;
        paintSprite(g2, sprite, cx, cy, pixel, color);
    }

    private static void paintCityFallbackWholeTile(Graphics2D g2, double cx, double cy) {
        double bx = cx - HEX_R * 0.78;
        double by = cy - HEX_R * 0.12;
        g2.setColor(new Color(255, 255, 255, 200));
        g2.fillRoundRect((int) bx, (int) by, (int) (HEX_R * 1.56), (int) (HEX_R * 0.42), 5, 5);
        g2.fillRect((int) (bx + HEX_R * 0.10), (int) (by - HEX_R * 0.22),
                (int) (HEX_R * 0.22), (int) (HEX_R * 0.42));
        g2.fillRect((int) (bx + HEX_R * 0.42), (int) (by - HEX_R * 0.32),
                (int) (HEX_R * 0.26), (int) (HEX_R * 0.52));
        g2.fillRect((int) (bx + HEX_R * 0.78), (int) (by - HEX_R * 0.18),
                (int) (HEX_R * 0.22), (int) (HEX_R * 0.38));
        g2.fillRect((int) (bx + HEX_R * 1.12), (int) (by - HEX_R * 0.26),
                (int) (HEX_R * 0.24), (int) (HEX_R * 0.46));
        g2.setColor(new Color(255, 245, 200, 228));
        g2.fillRect((int) (bx + HEX_R * 0.52), (int) (by - HEX_R * 0.44), 2, (int) (HEX_R * 0.14));
        var flag = new Path2D.Double();
        flag.moveTo(bx + HEX_R * 0.54, by - HEX_R * 0.44);
        flag.lineTo(bx + HEX_R * 0.74, by - HEX_R * 0.38);
        flag.lineTo(bx + HEX_R * 0.54, by - HEX_R * 0.30);
        flag.closePath();
        g2.fill(flag);
    }

    private static Color hpColor(double frac) {
        if (frac > 0.66) return new Color(0x3c_d0_5b);
        if (frac > 0.33) return new Color(0xf5_c2_3a);
        return new Color(0xe2_3e_3e);
    }

    /** Pseudo-elevation for lighting: lowlands / sea darker basins, high terrain brighter ridges. */
    private static float terrainElevationBias(Terrain t) {
        return switch (t) {
            case WATER -> -1f;
            case GRASS, PLAINS -> -0.22f;
            case DESERT -> 0f;
            case FOREST -> 0.28f;
            case HILL -> 0.52f;
            case MOUNTAIN -> 1f;
        };
    }

    private static Color tintTerrainForElevation(Color c, Terrain t) {
        float b = terrainElevationBias(t);
        if (Math.abs(b) < 0.02f) {
            return c;
        }
        if (b >= 0) {
            return blend(c, new Color(255, 255, 255), b * 0.26f);
        }
        return blend(c, new Color(10, 14, 24), -b * 0.29f);
    }

    /** Reinforces elevation on textured terrain after the global light grade. */
    private static void paintElevationBitmapWash(Graphics2D g2, double cx, double cy, Terrain t) {
        float b = terrainElevationBias(t);
        if (Math.abs(b) < 0.07f) {
            return;
        }
        var hex = hexPath(cx, cy);
        var oc = g2.getClip();
        g2.clip(hex);
        if (b > 0) {
            float rad = (float) (HEX_R * 0.95);
            var g = new RadialGradientPaint(
                    (float) cx, (float) cy, rad,
                    new float[] {0f, 1f},
                    new Color[] {
                            new Color(255, 255, 255, Math.min(40, (int) (18 * b))),
                            new Color(255, 255, 255, 0),
                    },
                    MultipleGradientPaint.CycleMethod.NO_CYCLE);
            g2.setPaint(g);
        } else {
            float rad = (float) (HEX_R * 0.95);
            var g = new RadialGradientPaint(
                    (float) cx, (float) cy, rad,
                    new float[] {0f, 1f},
                    new Color[] {
                            new Color(8, 12, 22, Math.min(54, (int) (22 * -b))),
                            new Color(255, 255, 255, 0),
                    },
                    MultipleGradientPaint.CycleMethod.NO_CYCLE);
            g2.setPaint(g);
        }
        g2.fill(hex);
        g2.setClip(oc);
    }

    private static Color blend(Color a, Color b, float t) {
        int r = (int) (a.getRed() * (1 - t) + b.getRed() * t);
        int g = (int) (a.getGreen() * (1 - t) + b.getGreen() * t);
        int bl = (int) (a.getBlue() * (1 - t) + b.getBlue() * t);
        return new Color(r, g, bl);
    }

    private void updateProjectedPathFromHover() {
        projectedPath = List.of();
        projectedTurns = 0;
        if (session == null || selectedUnitId < 0) return;
        var uOpt = session.unitById(selectedUnitId);
        if (uOpt.isEmpty()) return;
        Unit u = uOpt.get();
        if (u.ownerSeat() != session.currentPlayer().seat()) return;
        if (moveCommandMode) {
            HexCoord target = moveCursorLocked
                    ? moveCursor
                    : (hoveredHex != null ? hoveredHex : moveCursor);
            if (target == null) return;
            var p = projectedPathTo(u, target);
            projectedPath = p.path();
            projectedTurns = p.turns();
            return;
        }
        var route = session.plannedRouteFor(u.id());
        if (!route.isEmpty()) {
            var full = new ArrayList<HexCoord>();
            full.add(u.coord());
            full.addAll(route);
            projectedPath = full;
            projectedTurns = turnsForPath(u, full);
        }
    }

    private boolean tryFollowProjectedPath(Unit u, HexCoord dest) {
        var p = projectedPathTo(u, dest);
        if (p.path().size() < 2) return false;
        session.assignPlannedRoute(u.id(), p.path());
        return session.followPlannedRoute(u.id());
    }

    private record ProjectedPath(List<HexCoord> path, int turns) {}

    private ProjectedPath projectedPathTo(Unit unit, HexCoord dest) {
        if (unit.coord().equals(dest)) return new ProjectedPath(List.of(unit.coord()), 0);
        if (!session.map().contains(dest) || !session.terrainEffectiveAt(dest).passable()) {
            return new ProjectedPath(List.of(), 0);
        }
        if (session.unitAt(dest).isPresent()) return new ProjectedPath(List.of(), 0);
        if (session.wildAnimalAt(dest).isPresent() && unit.kind() != UnitKind.HUNTING_PARTY) {
            return new ProjectedPath(List.of(), 0);
        }

        HexCoord start = unit.coord();
        var dist = new HashMap<HexCoord, Integer>();
        var prev = new HashMap<HexCoord, HexCoord>();
        var pq = new PriorityQueue<Map.Entry<HexCoord, Integer>>(Comparator.comparingInt(Map.Entry::getValue));
        dist.put(start, 0);
        pq.add(Map.entry(start, 0));

        while (!pq.isEmpty()) {
            var cur = pq.poll();
            HexCoord c = cur.getKey();
            int d = cur.getValue();
            if (d != dist.getOrDefault(c, Integer.MAX_VALUE)) continue;
            if (c.equals(dest)) break;
            for (var n : c.neighbors()) {
                if (!session.map().contains(n)) continue;
                Terrain t = session.terrainEffectiveAt(n);
                if (!t.passable()) continue;
                if (!n.equals(dest) && session.unitAt(n).isPresent()) continue;
                if (!n.equals(dest) && session.wildAnimalAt(n).isPresent()) continue;
                int nd = d + session.movementCostForStep(unit, n);
                if (nd < dist.getOrDefault(n, Integer.MAX_VALUE)) {
                    dist.put(n, nd);
                    prev.put(n, c);
                    pq.add(Map.entry(n, nd));
                }
            }
        }

        Integer totalCost = dist.get(dest);
        if (totalCost == null) return new ProjectedPath(List.of(), 0);

        var rev = new ArrayList<HexCoord>();
        HexCoord cur = dest;
        rev.add(cur);
        while (!cur.equals(start)) {
            cur = prev.get(cur);
            if (cur == null) return new ProjectedPath(List.of(), 0);
            rev.add(cur);
        }
        var path = new ArrayList<HexCoord>(rev.size());
        for (int i = rev.size() - 1; i >= 0; i--) path.add(rev.get(i));

        int turns = turnsForPath(unit, path);
        return new ProjectedPath(path, turns);
    }

    private int turnsForPath(Unit unit, List<HexCoord> path) {
        if (path.size() < 2) return 0;
        int cost = 0;
        for (int i = 1; i < path.size(); i++) {
            cost += session.movementCostForStep(unit, path.get(i));
        }
        int perTurn = unit.kind().movement();
        int firstTurnMoves = Math.max(1, unit.movesRemaining());
        return cost <= firstTurnMoves ? 1 : 1 + (cost - firstTurnMoves + perTurn - 1) / perTurn;
    }

    private void paintProjectedPath(Graphics2D g2, List<HexCoord> path, int turns) {
        var old = g2.getStroke();
        for (int i = 0; i < path.size() - 1; i++) {
            double x1 = axialToPixelX(path.get(i));
            double y1 = axialToPixelY(path.get(i));
            double x2 = axialToPixelX(path.get(i + 1));
            double y2 = axialToPixelY(path.get(i + 1));
            g2.setStroke(new BasicStroke(lodDetail ? 5.2f : 4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(255, 255, 255, lodDetail ? 95 : 75));
            g2.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
            g2.setStroke(new BasicStroke(lodDetail ? 3f : 2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(45, 195, 255, lodDetail ? 228 : 200));
            g2.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
        }
        g2.setStroke(old);
        HexCoord end = path.get(path.size() - 1);
        double ex = axialToPixelX(end);
        double ey = axialToPixelY(end);
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRoundRect((int) ex - 16, (int) ey - 26, 32, 14, 6, 6);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g2.drawString(turns + "T", (float) ex - 8, (float) ey - 15);
    }

    private void paintWeatherOverlay(Graphics2D g2, Weather w, double cx, double cy, boolean inSight) {
        Color wash = weatherWashColor(w, inSight);
        var hex = hexPath(cx, cy);
        var oc = g2.getClip();
        g2.clip(hex);
        int a = wash.getAlpha();
        // Uniform wash — directional lit/dim mimicked sun-facing edges (especially drought) as bright “walls”.
        int fillA = Math.min(255, (int) (a * 0.90));
        g2.setColor(new Color(wash.getRed(), wash.getGreen(), wash.getBlue(), fillA));
        g2.fill(hex);
        g2.setClip(oc);
        char ch = w.badgeChar();
        if (ch != ' ') {
            double bx = cx + HEX_R * 0.38;
            double by = cy - HEX_R * 0.52;
            g2.setColor(new Color(18, 22, 30, 228));
            g2.fill(new RoundRectangle2D.Double(bx - 7, by - 11, 14, 14, 4, 4));
            g2.setColor(weatherBadgeInk(w));
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            g2.drawString(String.valueOf(ch), (float) (bx - 3.5f), (float) (by - 2));
        }
    }

    private static Color weatherWashColor(Weather w, boolean inSight) {
        int a = inSight ? 52 : 34;
        return switch (w) {
            case CLEAR -> new Color(255, 255, 255, 0);
            case RAIN -> new Color(70, 145, 255, a);
            case DROUGHT -> new Color(215, 165, 85, a);
            case STORM -> new Color(125, 95, 205, a + 10);
            case COLD_SNAP -> new Color(205, 232, 255, a);
            case HEAT_WAVE -> new Color(255, 115, 55, a);
            case FOG -> new Color(188, 190, 198, a + 8);
        };
    }

    private static Color weatherBadgeInk(Weather w) {
        return switch (w) {
            case RAIN -> new Color(215, 238, 255);
            case DROUGHT -> new Color(255, 235, 190);
            case STORM -> new Color(235, 225, 255);
            case COLD_SNAP -> new Color(225, 242, 255);
            case HEAT_WAVE -> new Color(255, 245, 230);
            case FOG -> new Color(235, 236, 242);
            case CLEAR -> Color.WHITE;
        };
    }

    private static String terrainName(Terrain t) {
        String n = t.name().toLowerCase();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** Reload catalog, assignments, and scanned sets; call after saving Settings. */
    void reloadGraphicAssetsFromDisk() {
        GraphicRuntime.reloadFromDisk();
        loadSprites();
        repaint();
    }

    private void loadSprites() {
        var scoutResolved = GraphicRuntime.resolveSlot(GraphicSlotIds.UNIT_SCOUT);
        scoutUsesLibraryArt = scoutResolved.source() == GraphicResolvedAsset.ResolvedSource.LIBRARY_SET_ART;
        scoutPng = loadPngForResolved(scoutResolved, "assets/graphics/scout.png");
        scoutSprite = loadTxtPrimaryOrLegacy(scoutResolved.txtSprite().orElse(null), "assets/graphics/scout.txt");

        var settlerResolved = GraphicRuntime.resolveSlot(GraphicSlotIds.UNIT_SETTLER);
        settlerUsesLibraryArt = settlerResolved.source() == GraphicResolvedAsset.ResolvedSource.LIBRARY_SET_ART;
        settlerPng = loadPngForResolved(settlerResolved, "assets/graphics/settler.png");
        settlerSprite = loadTxtPrimaryOrLegacy(settlerResolved.txtSprite().orElse(null), "assets/graphics/settler.txt");

        var cityResolved = GraphicRuntime.resolveSlot(GraphicSlotIds.CITY_BANNER);
        cityPng = loadPngForResolved(cityResolved, "assets/graphics/city.png");
        citySprite = loadTxtPrimaryOrLegacy(cityResolved.txtSprite().orElse(null), "assets/graphics/city.txt");

        var farmResolved = GraphicRuntime.resolveSlot(GraphicSlotIds.IMPROVEMENT_FARM);
        farmPng = loadPngForResolved(farmResolved, "assets/graphics/farm.png");
        farmSprite = loadTxtPrimaryOrLegacy(farmResolved.txtSprite().orElse(null), "assets/graphics/farm.txt");

        var warriorResolved = GraphicRuntime.resolveSlot(GraphicSlotIds.UNIT_WARRIOR);
        warriorUsesLibraryArt = warriorResolved.source() == GraphicResolvedAsset.ResolvedSource.LIBRARY_SET_ART;
        warriorPng = loadPngForResolved(warriorResolved, "assets/graphics/warrior.png");
        warriorSprite = loadTxtPrimaryOrLegacy(warriorResolved.txtSprite().orElse(null), "assets/graphics/warrior.txt");

        var farmerResolved = GraphicRuntime.resolveSlot(GraphicSlotIds.UNIT_FARMER);
        farmerUsesLibraryArt = farmerResolved.source() == GraphicResolvedAsset.ResolvedSource.LIBRARY_SET_ART;
        farmerPng = loadPngForResolved(farmerResolved, "assets/graphics/farmer.png");
        farmerSprite = loadTxtPrimaryOrLegacy(farmerResolved.txtSprite().orElse(null), "assets/graphics/farmer.txt");

        var builderResolved = GraphicRuntime.resolveSlot(GraphicSlotIds.UNIT_BUILDER);
        builderUsesLibraryArt = builderResolved.source() == GraphicResolvedAsset.ResolvedSource.LIBRARY_SET_ART;
        builderPng = loadPngForResolved(builderResolved, "assets/graphics/builder.png");
        builderSprite = loadTxtPrimaryOrLegacy(builderResolved.txtSprite().orElse(null), "assets/graphics/builder.txt");

        var mineResolved = GraphicRuntime.resolveSlot(GraphicSlotIds.IMPROVEMENT_MINE);
        minePng = loadPngForResolved(mineResolved, "assets/graphics/mine.png");
        mineSprite = loadTxtPrimaryOrLegacy(mineResolved.txtSprite().orElse(null), "assets/graphics/mine.txt");

        var wildlifeResolved = GraphicRuntime.resolveSlot(GraphicSlotIds.WILDLIFE_TOKEN);
        wildlifePng = loadPngForResolved(wildlifeResolved, "assets/graphics/wildlife.png");
        wildlifeSprite = loadTxtPrimaryOrLegacy(wildlifeResolved.txtSprite().orElse(null), "assets/graphics/wildlife.txt");

        terrainWaterPng = prepareTerrainTexture(loadPngForResolved(
                GraphicRuntime.resolveSlot(GraphicSlotIds.TERRAIN_WATER), "assets/graphics/terrain_water.png"));
        terrainGrassPng = prepareTerrainTexture(loadPngForResolved(
                GraphicRuntime.resolveSlot(GraphicSlotIds.TERRAIN_GRASS), "assets/graphics/terrain_grass.png"));
        terrainPlainsPng = prepareTerrainTexture(loadPngForResolved(
                GraphicRuntime.resolveSlot(GraphicSlotIds.TERRAIN_PLAINS), "assets/graphics/terrain_plains.png"));
        terrainDesertPng = prepareTerrainTexture(loadPngForResolved(
                GraphicRuntime.resolveSlot(GraphicSlotIds.TERRAIN_DESERT), "assets/graphics/terrain_desert.png"));
        terrainHillPng = prepareTerrainTexture(loadPngForResolved(
                GraphicRuntime.resolveSlot(GraphicSlotIds.TERRAIN_HILL), "assets/graphics/terrain_hill.png"));
        terrainForestPng = prepareTerrainTexture(loadPngForResolved(
                GraphicRuntime.resolveSlot(GraphicSlotIds.TERRAIN_FOREST), "assets/graphics/terrain_forest.png"));
        terrainMountainPng = prepareTerrainTexture(loadPngForResolved(
                GraphicRuntime.resolveSlot(GraphicSlotIds.TERRAIN_MOUNTAIN), "assets/graphics/terrain_mountain.png"));
    }

    private BufferedImage terrainImageFor(Terrain t) {
        return switch (t) {
            case WATER -> terrainWaterPng;
            case GRASS -> terrainGrassPng;
            case PLAINS -> terrainPlainsPng;
            case DESERT -> terrainDesertPng;
            case HILL -> terrainHillPng;
            case FOREST -> terrainForestPng;
            case MOUNTAIN -> terrainMountainPng;
        };
    }

    /**
     * Chat-style terrain outputs are often rendered over opaque black and/or padded heavily. Normalize these images so
     * they fill a hex naturally by optionally keying out obvious black background and trimming transparent padding.
     */
    private static BufferedImage prepareTerrainTexture(BufferedImage src) {
        if (src == null) return null;
        BufferedImage keyed = maybeKeyOutNearBlackBackground(src);
        BufferedImage trimmed = trimTransparentBounds(keyed);
        return trimmed == null ? keyed : trimmed;
    }

    private static BufferedImage maybeKeyOutNearBlackBackground(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 2 || h < 2) return src;

        // Only attempt keying when all four corners are dark/opaque enough to likely be a synthetic backdrop.
        int[] corners = new int[] {
                src.getRGB(0, 0),
                src.getRGB(w - 1, 0),
                src.getRGB(0, h - 1),
                src.getRGB(w - 1, h - 1)
        };
        for (int argb : corners) {
            int a = (argb >>> 24) & 0xff;
            int r = (argb >>> 16) & 0xff;
            int g = (argb >>> 8) & 0xff;
            int b = argb & 0xff;
            if (a < 200 || r > 28 || g > 28 || b > 28) {
                return src;
            }
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int r = (argb >>> 16) & 0xff;
                int g = (argb >>> 8) & 0xff;
                int b = argb & 0xff;
                if (a > 0 && r <= 24 && g <= 24 && b <= 24) {
                    out.setRGB(x, y, 0x00000000);
                } else {
                    out.setRGB(x, y, argb);
                }
            }
        }
        return out;
    }

    private static BufferedImage trimTransparentBounds(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = (src.getRGB(x, y) >>> 24) & 0xff;
                if (a > 0) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return src;
        }
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) {
            return src;
        }
        return src.getSubimage(minX, minY, (maxX - minX + 1), (maxY - minY + 1));
    }

    private boolean usesLibraryUnitArt(UnitKind kind) {
        return switch (kind) {
            case SCOUT -> scoutUsesLibraryArt;
            case SETTLER -> settlerUsesLibraryArt;
            case WARRIOR -> warriorUsesLibraryArt;
            case FARMER -> farmerUsesLibraryArt;
            case BUILDER -> builderUsesLibraryArt;
            case HUNTING_PARTY -> false;
        };
    }

    /**
     * Loads PNG for resolved art. When the active assignment is a library set that only supplies a TXT sprite (common
     * when the model omitted PNG or the file is missing), we must not fall back to bundled catalog PNG — paint order
     * prefers PNG over sprite, so bundled art would hide the library sprite entirely.
     */
    private static BufferedImage loadPngForResolved(GraphicResolvedAsset resolved, String legacyRelativePng) {
        Optional<Path> pngOpt = resolved.png();
        if (pngOpt.isPresent()) {
            BufferedImage img = loadPngAt(pngOpt.get());
            if (img != null) {
                return img;
            }
            if (resolved.source() == GraphicResolvedAsset.ResolvedSource.LIBRARY_SET_ART
                    && resolved.txtSprite().isPresent()) {
                return null;
            }
        } else if (resolved.source() == GraphicResolvedAsset.ResolvedSource.LIBRARY_SET_ART
                && resolved.txtSprite().isPresent()) {
            return null;
        }
        return loadPngRel(legacyRelativePng);
    }

    private static BufferedImage loadPngAt(Path path) {
        try {
            if (!Files.isRegularFile(path)) return null;
            return ImageIO.read(path.toFile());
        } catch (IOException ex) {
            return null;
        }
    }

    private static BufferedImage loadPngRel(String relativePath) {
        try {
            Path p = Path.of(relativePath);
            if (!Files.exists(p)) {
                Path alt = Path.of("tack-and-strat", relativePath.replace('\\', '/'));
                if (!Files.exists(alt)) return null;
                return ImageIO.read(alt.toFile());
            }
            return ImageIO.read(p.toFile());
        } catch (IOException ex) {
            return null;
        }
    }

    private static PixelSprite loadTxtPrimaryOrLegacy(Path primary, String legacyRelative) {
        if (primary != null) {
            PixelSprite spr = loadSpriteAt(primary);
            if (spr != null) return spr;
        }
        return loadSpriteRel(legacyRelative);
    }

    private static PixelSprite loadSpriteAt(Path p) {
        if (!Files.isRegularFile(p)) return null;
        try {
            List<String> rows = Files.readAllLines(p);
            return spriteFromLines(rows);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static PixelSprite loadSpriteRel(String relativePath) {
        Path p = Path.of(relativePath);
        if (!Files.isRegularFile(p)) {
            p = Path.of("tack-and-strat", relativePath.replace('\\', '/'));
        }
        return loadSpriteAt(p);
    }

    private static PixelSprite spriteFromLines(List<String> rows) {
        if (rows.isEmpty()) return null;
        int h = rows.size();
        int w = rows.stream().mapToInt(String::length).max().orElse(0);
        if (w == 0) return null;
        boolean[][] pixels = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            String row = rows.get(y);
            for (int x = 0; x < row.length(); x++) {
                char ch = row.charAt(x);
                pixels[y][x] = ch == '#' || ch == 'X' || ch == '1' || ch == '@';
            }
        }
        return new PixelSprite(w, h, pixels);
    }

    private static void paintSprite(Graphics2D g2, PixelSprite sprite, double cx, double cy, double pixel, Color color) {
        if (sprite == null) return;
        double startX = cx - (sprite.w * pixel) / 2.0;
        double startY = cy - (sprite.h * pixel) / 2.0;
        g2.setColor(color);
        for (int y = 0; y < sprite.h; y++) {
            for (int x = 0; x < sprite.w; x++) {
                if (!sprite.pixels[y][x]) continue;
                g2.fill(new java.awt.geom.Rectangle2D.Double(
                        startX + x * pixel,
                        startY + y * pixel,
                        pixel + 0.2,
                        pixel + 0.2));
            }
        }
    }

    private static void paintImage(Graphics2D g2, BufferedImage img, double cx, double cy, double scale) {
        paintImage(g2, img, cx, cy, scale, false, false);
    }

    /**
     * Draws a scaled PNG; {@code grounded} adds contact shadow under the feet and a soft rim so sprites match map lighting.
     */
    private static void paintImage(Graphics2D g2, BufferedImage img, double cx, double cy, double scale, boolean grounded) {
        paintImage(g2, img, cx, cy, scale, grounded, false);
    }

    private static void paintImage(Graphics2D g2, BufferedImage img, double cx, double cy, double scale,
            boolean grounded, boolean mirrorX) {
        if (img == null) return;
        int w = (int) Math.max(8, img.getWidth() * scale);
        int h = (int) Math.max(8, img.getHeight() * scale);
        // User uploads can be very large (e.g. 1k-2k px). Clamp to tile-relative bounds so unit art
        // always stays inside a single-hex footprint regardless of source image resolution.
        double maxW = HEX_R * 1.70;
        double maxH = HEX_R * 1.90;
        double clamp = Math.min(1.0, Math.min(maxW / w, maxH / h));
        if (clamp < 1.0) {
            w = Math.max(8, (int) Math.round(w * clamp));
            h = Math.max(8, (int) Math.round(h * clamp));
        }
        int x = (int) Math.round(cx - w / 2.0);
        int y = (int) Math.round(cy - h / 2.0);
        if (grounded) {
            double footY = y + h * 0.88;
            double footCx = x + w * 0.5;
            double ox = HEX_R * 0.08;
            double oy = HEX_R * 0.11;
            paintGroundEllipseShadow(g2, footCx, footY, w * 0.36, h * 0.11, ox, oy, 52);
            paintGroundEllipseShadow(g2, footCx, footY - h * 0.02, w * 0.40, h * 0.13, ox * 0.55, oy * 0.65, 30);
        }
        var savedTx = g2.getTransform();
        if (mirrorX) {
            g2.translate(cx, cy);
            g2.scale(-1, 1);
            g2.translate(-cx, -cy);
        }
        g2.drawImage(img, x, y, w, h, null);
        if (grounded) {
            var oldS = g2.getStroke();
            g2.setStroke(new BasicStroke(1.05f));
            int arc = Math.max(5, Math.min(w, h) / 14);
            g2.setColor(new Color(255, 255, 255, 76));
            g2.drawRoundRect(x + 1, y + 1, w - 2, h - 2, arc, arc);
            g2.setStroke(oldS);
        }
        g2.setTransform(savedTx);
    }

    /** Horizontal flip when the queued route steps screen-left (planned-route waypoint). */
    private boolean unitSpriteMirrorForRoute(Unit u) {
        if (session == null) {
            return false;
        }
        var route = session.plannedRouteFor(u.id());
        if (route.isEmpty()) {
            return false;
        }
        HexCoord next = route.get(0);
        double dx = axialToPixelX(next) - axialToPixelX(u.coord());
        return dx < -0.25;
    }

    private void paintUnitSymbol(Graphics2D g2, Unit u, double cx, double cy) {
        boolean mirror = unitSpriteMirrorForRoute(u);
        switch (u.kind()) {
            case SCOUT -> {
                if (scoutPng != null) paintImage(g2, scoutPng, cx, cy, 0.7, true, mirror);
                else if (scoutSprite != null) paintSprite(g2, scoutSprite, cx, cy, 1.05, new Color(255, 255, 255, 235));
                else paintScoutSymbol(g2, cx, cy);
            }
            case SETTLER -> {
                if (settlerPng != null) paintImage(g2, settlerPng, cx, cy, 0.7, true, mirror);
                else if (settlerSprite != null) paintSprite(g2, settlerSprite, cx, cy, 1.05, new Color(255, 255, 255, 235));
                else paintSettlerSymbol(g2, cx, cy);
            }
            case WARRIOR -> {
                if (warriorPng != null) paintImage(g2, warriorPng, cx, cy, 0.7, true, mirror);
                else if (warriorSprite != null) {
                    paintSprite(g2, warriorSprite, cx, cy, 1.05, new Color(255, 255, 255, 235));
                } else {
                    paintKindGlyph(g2, u.kind(), cx, cy);
                }
            }
            case FARMER -> {
                if (farmerPng != null) paintImage(g2, farmerPng, cx, cy, 0.7, true, mirror);
                else if (farmerSprite != null) {
                    paintSprite(g2, farmerSprite, cx, cy, 1.05, new Color(255, 255, 255, 235));
                } else {
                    paintKindGlyph(g2, u.kind(), cx, cy);
                }
            }
            case BUILDER -> {
                if (builderPng != null) paintImage(g2, builderPng, cx, cy, 0.7, true, mirror);
                else if (builderSprite != null) {
                    paintSprite(g2, builderSprite, cx, cy, 1.05, new Color(255, 255, 255, 235));
                } else {
                    paintKindGlyph(g2, u.kind(), cx, cy);
                }
            }
            case HUNTING_PARTY -> paintKindGlyph(g2, u.kind(), cx, cy);
        }
    }

    private static void paintKindGlyph(Graphics2D g2, UnitKind kind, double cx, double cy) {
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (HEX_R * 0.55)));
        String ch = String.valueOf(kind.glyph());
        var fm = g2.getFontMetrics();
        int w = fm.stringWidth(ch);
        g2.drawString(ch, (float) (cx - w / 2.0), (float) (cy + fm.getAscent() / 2.0 - 3));
    }

    private static void paintScoutSymbol(Graphics2D g2, double cx, double cy) {
        g2.setColor(new Color(255, 255, 255, 235));
        g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine((int) (cx - 7), (int) (cy + 7), (int) (cx + 7), (int) (cy - 7));
        g2.fillOval((int) (cx - 9), (int) (cy - 2), 6, 6);
        g2.fillOval((int) (cx + 3), (int) (cy - 10), 6, 6);
    }

    private static void paintSettlerSymbol(Graphics2D g2, double cx, double cy) {
        g2.setColor(new Color(255, 255, 255, 230));
        g2.fillRoundRect((int) (cx - 8), (int) (cy - 5), 16, 8, 3, 3);
        g2.fillRect((int) (cx - 2), (int) (cy - 10), 4, 5);
        var pennant = new Path2D.Double();
        pennant.moveTo(cx + 2, cy - 10);
        pennant.lineTo(cx + 9, cy - 8);
        pennant.lineTo(cx + 2, cy - 6);
        pennant.closePath();
        g2.fill(pennant);
        g2.fillOval((int) (cx - 7), (int) (cy + 2), 5, 5);
        g2.fillOval((int) (cx + 2), (int) (cy + 2), 5, 5);
    }

    interface SelectionListener {
        void selectionChanged();
    }

    private record PixelSprite(int w, int h, boolean[][] pixels) {}

    /** Convenience: in case future code wants the screen→world transform. */
    @SuppressWarnings("unused")
    private Point2D screenToWorld(double sx, double sy) {
        try {
            var t = new AffineTransform();
            t.translate(viewOffsetX, viewOffsetY);
            t.scale(viewScale, viewScale);
            return t.inverseTransform(new Point2D.Double(sx, sy), null);
        } catch (NoninvertibleTransformException e) {
            return new Point2D.Double(sx, sy);
        }
    }

    @SuppressWarnings("unused")
    Rectangle visibleWorldBounds() {
        return new Rectangle(
                (int) ((-viewOffsetX) / viewScale),
                (int) ((-viewOffsetY) / viewScale),
                (int) (getWidth() / viewScale),
                (int) (getHeight() / viewScale));
    }
}
