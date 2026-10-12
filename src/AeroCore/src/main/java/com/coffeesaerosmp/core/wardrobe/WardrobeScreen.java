package com.coffeesaerosmp.core.wardrobe;

import com.coffeesaerosmp.core.client.AeroButton;
import com.coffeesaerosmp.core.wardrobe.WardrobeData.Cat;
import com.coffeesaerosmp.core.wardrobe.WardrobeData.Item;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Wardrobe: the Coffee Bean shop with a live preview of YOU wearing it. Opened by the server
 * ({@code aerosmp:wardrobe} payload) when a premium player runs {@code /shop}.
 *
 * <p>Left: the preview. "You" shows your own 3D model (drag to spin) with your nameplate above in the selected
 * colour or gradient and the selected trail at your feet; "Airship" shows a little ship with the selected contrail
 * streaming behind it. Right: categories, items with their status, and the actions.
 *
 * <p>Everything you see in the preview is drawn here on your PC: no packets while the screen is open, a fixed
 * particle pool, and nothing runs once it's closed. Every action is a normal command the server checks
 * ({@code /cosmetics …}, {@code /shop purchase}, {@code /shop tryon}), so the screen can't do anything a player
 * couldn't do by typing.
 */
public class WardrobeScreen extends Screen {

    // Palette: the pack's dark oak + brass.
    static final int PANEL_TOP = 0xF0261A0E, PANEL_BOT = 0xF0170F07, BRASS = 0xFF9C7430, BRASS_HI = 0xFFF0C05A;
    static final int TEXT = 0xFFEADCC3, MUTED = 0xFFA89880, GOLD = 0xFFF5C451, GREEN = 0xFF7BD88F, PURPLE = 0xFFD39BFF;

    private static final Cat[] TABS = {Cat.TRAILS, Cat.CONTRAILS, Cat.COLOURS, Cat.GRADIENTS, Cat.EXTRAS, null};
    private static final String[] TAB_LABELS = {"Trails", "Contrails", "Colours", "Gradients", "Extras", "Owned"};
    private static final int ROW_H = 22;

    private WardrobeData data;
    private int tab = 0;                 // index into TABS
    private String selectedId;
    private boolean airshipView;
    private double scroll;
    private String confirmId;            // non-null while the buy dialog shows
    private String pendingBuy;
    private String status = "";
    private long statusUntil;

    // Preview state
    private final GuiParticles particles = new GuiParticles();
    private float yaw = 0f, shipYaw = 25f, dragYaw = 0f, shipDrag = 0f;
    private float spinT = 0f;
    private boolean dragging;
    private long lastFrameNs;
    private final Map<String, ItemStack> iconCache = new HashMap<>();

    // Layout (computed in init)
    private int pvX, pvY, pvW, pvH, rx, ry, rw, listY, listH, detY, detH;

    public WardrobeScreen(WardrobeData data) {
        super(Component.literal("Wardrobe"));
        this.data = data;
        Item first = firstEquippedOrFirst(Cat.TRAILS);
        this.selectedId = first == null ? null : first.id();
    }

    /** New data from the server while open (after a purchase, a wear, a rank change). */
    public void refresh(WardrobeData d) {
        this.data = d;
        if (pendingBuy != null) {
            Item it = d.byId(pendingBuy);
            if (it != null && it.available()) toast("Bought " + it.label() + "!");
            pendingBuy = null;
        }
        rebuildWidgets();
    }

    // ── layout + widgets ──────────────────────────────────────────────────────

    @Override
    protected void init() {
        int pad = 6, top = 24;
        pvW = Math.max(150, Math.min(260, (int) (width * 0.40)));
        pvX = pad;
        pvY = top + 4;
        pvH = height - pvY - pad;
        rx = pvX + pvW + pad;
        ry = pvY;
        rw = width - rx - pad;
        detH = 56;
        listY = ry + 22;
        detY = height - pad - detH;
        listH = detY - 4 - listY;

        // While the buy dialog is open it owns the screen: only its two buttons exist.
        if (confirmId != null) {
            Item it = data.byId(confirmId);
            if (it == null) { confirmId = null; } else {
                int cw = 220, ch = 84, cx = width / 2 - cw / 2, cy = height / 2 - ch / 2;
                AeroButton buy = AeroButton.aero(Component.literal("Buy"), bb -> doBuy())
                    .bounds(cx + 16, cy + ch - 26, 88, 18).plain().build();
                buy.active = data.balance < 0 || data.balance >= it.price();
                addRenderableWidget(buy);
                addRenderableWidget(AeroButton.aero(Component.literal("Cancel"), bb -> { confirmId = null; rebuildWidgets(); })
                    .bounds(cx + cw - 16 - 88, cy + ch - 26, 88, 18).plain().build());
                return;
            }
        }

        // Top bar
        int bx = width - pad - 20;
        addRenderableWidget(AeroButton.aero(Component.literal("✕"), b -> onClose()).bounds(bx, 4, 20, 16).plain().build());
        addRenderableWidget(AeroButton.aero(Component.literal("Classic shop"), b -> { send("shop classic"); onClose(); })
            .bounds(bx - 4 - 80, 4, 80, 16).plain().build());
        addRenderableWidget(AeroButton.aero(Component.literal("Get Beans"), b -> openStore())
            .bounds(bx - 8 - 80 - 70, 4, 70, 16).plain().build());

        // Preview mode
        int half = (pvW - 12) / 2;
        addRenderableWidget(AeroButton.aero(Component.literal("You"), b -> { airshipView = false; particles.clear(); })
            .bounds(pvX + 4, pvY + 4, half, 14).plain().build());
        addRenderableWidget(AeroButton.aero(Component.literal("Airship"), b -> { airshipView = true; particles.clear(); })
            .bounds(pvX + 8 + half, pvY + 4, half, 14).plain().build());

        // Category tabs
        int tw = rw / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            addRenderableWidget(AeroButton.aero(Component.literal(TAB_LABELS[i]), b -> selectTab(idx))
                .bounds(rx + i * tw, ry, tw - 2, 18).plain().build());
        }

        // Detail actions
        Item sel = selected();
        if (sel != null) {
            int by = detY + detH - 22;
            int bw = Math.min(120, (rw - 18) / 2);
            String primary = primaryLabel(sel);
            if (primary != null) {
                AeroButton p = AeroButton.aero(Component.literal(primary), b -> primaryAction(sel))
                    .bounds(rx + 6, by, bw, 18).plain().build();
                p.active = sel.available() || (sel.forSale() && pendingBuy == null);
                addRenderableWidget(p);
            }
            if (sel.particle()) {
                addRenderableWidget(AeroButton.aero(Component.literal("Try in world"), b -> {
                    send("shop tryon " + sel.id());
                    onClose();
                }).bounds(rx + 12 + bw, by, bw, 18).plain().build());
            }
        }
    }

    private String primaryLabel(Item it) {
        if (it.available()) {
            if (it.id().equals("join_message")) return "Write them";
            return it.equipped() ? "Take off" : "Wear";
        }
        if (it.forSale()) return pendingBuy != null ? "Buying…" : "Buy · " + it.price() + " Beans";
        if (it.rank() != null) return "Comes with " + it.rank();
        return "Event only";
    }

    // ── actions ───────────────────────────────────────────────────────────────

    private void primaryAction(Item it) {
        if (it.available()) { wear(it); return; }
        if (it.forSale()) { confirmId = it.id(); rebuildWidgets(); }
    }

    private void wear(Item it) {
        String id = it.id();
        String cmd = null;
        if (id.startsWith("trail:")) cmd = "cosmetics trail " + (it.equipped() ? "off" : id.substring(6));
        else if (id.startsWith("contrail:")) cmd = "cosmetics contrail " + (it.equipped() ? "off" : id.substring(9));
        else if (id.startsWith("colour:")) cmd = it.equipped() ? "cosmetics clear" : "cosmetics colour " + id.substring(7);
        else if (id.startsWith("gradient:")) cmd = it.equipped() ? "cosmetics clear" : "cosmetics gradient " + id.substring(9);
        else if (id.equals("bold")) cmd = "cosmetics bold " + !it.equipped();
        else if (id.equals("join_message")) {
            minecraft.setScreen(new ChatScreen("/cosmetics joinmsg "));
            return;
        }
        if (cmd == null) return;
        send(cmd);
        toast(it.equipped() ? "Took off " + it.label() : "Wearing " + it.label());
        // The server sends a refresh once it has saved; until then show the change straight away.
        markEquipped(it, !it.equipped());
        rebuildWidgets();
    }

    private void doBuy() {
        if (confirmId == null) return;
        pendingBuy = confirmId;
        send("shop purchase " + confirmId);
        toast("Buying…");
        confirmId = null;
        rebuildWidgets();
    }

    private void openStore() {
        if (data.storeUrl == null || data.storeUrl.isBlank()) { toast("The store page isn't open yet."); return; }
        ConfirmLinkScreen.confirmLinkNow(this, data.storeUrl);
    }

    private void send(String cmd) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && p.connection != null) p.connection.sendCommand(cmd);
    }

    private void toast(String s) {
        status = s;
        statusUntil = System.currentTimeMillis() + 3500;
    }

    /** Local, optimistic equip state (the server's refresh replaces it moments later). */
    private void markEquipped(Item it, boolean on) {
        List<Item> next = new ArrayList<>();
        boolean exclusiveName = it.cat() == Cat.COLOURS || it.cat() == Cat.GRADIENTS;
        for (Item i : data.items) {
            boolean eq = i.equipped();
            if (i.id().equals(it.id())) eq = on;
            else if (on && i.cat() == it.cat() && it.cat() != Cat.EXTRAS) eq = false;
            else if (on && exclusiveName && (i.cat() == Cat.COLOURS || i.cat() == Cat.GRADIENTS)) eq = false;
            next.add(new Item(i.id(), i.label(), i.blurb(), i.cat(), i.price(), i.rank(), i.owned(), i.available(), eq,
                i.icon(), i.fx(), i.nameRgb()));
        }
        data.items.clear();
        data.items.addAll(next);
        if (it.id().startsWith("trail:")) data.eqTrail = on ? it.id() : null;
        if (it.id().startsWith("contrail:")) data.eqContrail = on ? it.id() : null;
        if (exclusiveName) { data.eqNameStyle = on ? it.id() : null; data.nameRgb = on ? it.nameRgb() : new int[0]; }
        if (it.id().equals("bold")) data.eqBold = on;
    }

    // ── harness / navigation ──────────────────────────────────────────────────

    public void selectTab(int idx) {
        confirmId = null;                                   // navigating away closes the buy dialog
        tab = Math.max(0, Math.min(TABS.length - 1, idx));
        scroll = 0;
        List<Item> rows = rows();
        Item pick = null;
        for (Item i : rows) if (i.equipped()) { pick = i; break; }
        if (pick == null && !rows.isEmpty()) pick = rows.get(0);
        selectedId = pick == null ? null : pick.id();
        if (TABS[tab] == Cat.CONTRAILS) airshipView = true;
        else if (TABS[tab] == Cat.TRAILS || TABS[tab] == Cat.COLOURS || TABS[tab] == Cat.GRADIENTS) airshipView = false;
        particles.clear();
        rebuildWidgets();
    }

    public void select(String id) {
        if (!id.equals(confirmId)) confirmId = null;
        selectedId = id;
        Item it = selected();
        if (it != null && it.cat() == Cat.CONTRAILS) airshipView = true;
        else if (it != null && it.cat() == Cat.TRAILS) airshipView = false;
        particles.clear();
        rebuildWidgets();
    }

    public void showConfirm(String id) { confirmId = id; rebuildWidgets(); }

    public void setAirshipView(boolean on) { airshipView = on; particles.clear(); }

    private List<Item> rows() {
        Cat c = TABS[tab];
        return c == null ? data.owned() : data.in(c);
    }

    private Item selected() { return data.byId(selectedId); }

    private Item firstEquippedOrFirst(Cat c) {
        List<Item> l = data.in(c);
        for (Item i : l) if (i.equipped()) return i;
        return l.isEmpty() ? null : l.get(0);
    }

    // ── input ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (confirmId != null) return super.mouseClicked(mx, my, button);
        if (super.mouseClicked(mx, my, button)) return true;
        if (mx >= pvX && mx < pvX + pvW && my >= pvY + 22 && my < pvY + pvH) { dragging = true; return true; }
        if (mx >= rx && mx < rx + rw && my >= listY && my < listY + listH) {
            int idx = (int) ((my - listY + scroll) / ROW_H);
            List<Item> rows = rows();
            if (idx >= 0 && idx < rows.size()) {
                Item it = rows.get(idx);
                if (it.id().equals(selectedId) && button == 0 && it.available()) wear(it);   // second click wears
                else select(it.id());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            if (airshipView) shipDrag += (float) dx * 1.5f; else dragYaw += (float) dx * 1.5f;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= rx && my >= listY && my < listY + listH) {
            int max = Math.max(0, rows().size() * ROW_H - listH);
            scroll = Math.max(0, Math.min(max, scroll - sy * ROW_H));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0.016f : Math.min(0.05f, (now - lastFrameNs) / 1e9f);
        lastFrameNs = now;

        super.render(g, mouseX, mouseY, partialTick);       // background + widgets
        // Title bar
        g.drawString(font, Component.literal("☕ Wardrobe"), 8, 8, GOLD, true);
        String bal = data.balance < 0 ? "—" : String.format("%,d", data.balance);
        String chip = bal + " Beans" + (data.rankLabel.isEmpty() ? "" : "   " + data.rankBadge + " " + data.rankLabel);
        g.drawString(font, chip, 8 + font.width("☕ Wardrobe") + 14, 8, TEXT, true);

        panel(g, pvX, pvY + 20, pvW, pvH - 20);
        panel(g, rx, listY - 2, rw, listH + 4);
        panel(g, rx, detY, rw, detH);
        underlineTab(g);

        if (airshipView) renderAirship(g, dt); else renderYou(g, mouseX, mouseY, dt);
        renderList(g, mouseX, mouseY);
        renderDetail(g);

        if (System.currentTimeMillis() < statusUntil) {
            int w = font.width(status) + 10;
            g.fill(pvX + 4, pvY + pvH - 18, pvX + 4 + w, pvY + pvH - 4, 0xC0000000);
            g.drawString(font, status, pvX + 9, pvY + pvH - 15, GOLD, false);
        }
        if (confirmId != null) renderConfirm(g, mouseX, mouseY, partialTick);
    }

    /** The world blurs behind the screen; the panels sit on top. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
    }

    private void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF120B05);
        g.fillGradient(x, y, x + w, y + h, PANEL_TOP, PANEL_BOT);
        g.fill(x, y, x + w, y + 1, BRASS);
        g.fill(x, y + h - 1, x + w, y + h, BRASS);
        g.fill(x, y, x + 1, y + h, BRASS);
        g.fill(x + w - 1, y, x + w, y + h, BRASS);
    }

    private void underlineTab(GuiGraphics g) {
        int tw = rw / TABS.length;
        g.fill(rx + tab * tw + 2, ry + 17, rx + tab * tw + tw - 4, ry + 19, BRASS_HI);
        g.fill(airshipView ? pvX + 8 + (pvW - 12) / 2 : pvX + 4, pvY + 18,
            (airshipView ? pvX + 8 + (pvW - 12) / 2 : pvX + 4) + (pvW - 12) / 2, pvY + 20, BRASS_HI);
    }

    // You: the player's model, nameplate and trail.
    private void renderYou(GuiGraphics g, int mouseX, int mouseY, float dt) {
        LocalPlayer player = Minecraft.getInstance().player;
        int areaTop = pvY + 22, areaH = pvH - 22;
        float cx = pvX + pvW / 2f;
        float scale = Math.min(areaH * 0.30f, pvW * 0.36f);
        float cy = areaTop + areaH * 0.56f;

        // Floor shadow
        int fy = (int) (cy + scale * 0.92f);
        g.fill((int) (cx - scale * 0.55f), fy, (int) (cx + scale * 0.55f), fy + 2, 0x55000000);
        g.fill((int) (cx - scale * 0.35f), fy + 2, (int) (cx + scale * 0.35f), fy + 3, 0x33000000);

        // Trail under the feet: streams to one side as if walking.
        Item trail = previewTrail();
        if (trail != null && trail.fx() != null) {
            // Streams out behind, as if you're walking towards the right.
            particles.emit(trail.fx(), cx + scale * 0.1f, fy - scale * 0.08f, dt, -scale * 0.3f, 0f, scale);
        }
        particles.update(dt);

        if (player == null) {
            g.drawCenteredString(font, "Join a world to see yourself here", (int) cx, (int) cy, MUTED);
        } else {
            // Mostly facing you, swaying gently so the 3D reads; drag to turn all the way round.
            if (!dragging) spinT += dt;
            yaw = dragYaw + (float) Math.sin(spinT * 0.7f) * 32f;
            renderPlayer(g, player, cx, cy, scale);
        }
        // Particles in FRONT of the model: rising flames and puffs would otherwise hide behind the legs.
        g.enableScissor(pvX + 1, areaTop, pvX + pvW - 1, pvY + pvH - 1);
        g.pose().pushPose();
        g.pose().translate(0, 0, 220);
        particles.render(g);
        g.pose().popPose();
        g.disableScissor();

        // Nameplate above the head, in the previewed style.
        Component plate = nameplate();
        float ps = 1.5f;
        int nw = font.width(plate);
        float ny = cy - scale * 1.05f - 18;
        g.pose().pushPose();
        g.pose().translate(cx, ny, 200);
        g.pose().scale(ps, ps, 1);
        g.fill(-nw / 2 - 2, -1, nw / 2 + 2, 9, 0x40000000);
        g.drawString(font, plate, -nw / 2, 0, 0xFFFFFFFF, false);
        g.pose().popPose();
        g.drawCenteredString(font, Component.literal("drag to turn"), (int) cx, pvY + pvH - 12, 0xFF6E6252);
    }

    private void renderPlayer(GuiGraphics g, LocalPlayer e, float cx, float cy, float scale) {
        float bodyO = e.yBodyRot, yRotO = e.getYRot(), xRotO = e.getXRot(), headO = e.yHeadRot, headPrevO = e.yHeadRotO;
        try {
            e.yBodyRot = 180f + yaw;
            e.setYRot(180f + yaw);
            e.setXRot(0);
            e.yHeadRot = e.getYRot();
            e.yHeadRotO = e.getYRot();
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf cam = new Quaternionf().rotateX(-8f * (float) Math.PI / 180f);
            pose.mul(cam);
            InventoryScreen.renderEntityInInventory(g, cx, cy, scale, new Vector3f(0, e.getBbHeight() / 2f + 0.0625f, 0),
                pose, cam, e);
        } finally {
            e.yBodyRot = bodyO;
            e.setYRot(yRotO);
            e.setXRot(xRotO);
            e.yHeadRot = headO;
            e.yHeadRotO = headPrevO;
        }
    }

    private void renderAirship(GuiGraphics g, float dt) {
        int areaTop = pvY + 22, areaH = pvH - 22;
        g.fillGradient(pvX + 1, areaTop, pvX + pvW - 1, pvY + pvH - 1, 0xF03C6E9E, 0xF0A9CBE6);   // sky
        // Ship right of centre so its wake has the whole panel to stream across.
        float cx = pvX + pvW * 0.62f, cy = areaTop + areaH * 0.48f;
        float scale = Math.min(pvW / 15f, areaH / 12f);
        if (!dragging) spinT += dt;
        shipYaw = shipDrag + 25f + (float) Math.sin(spinT * 0.5f) * 30f;     // heading right, swaying; drag to turn
        float bob = (float) Math.sin(System.currentTimeMillis() / 700.0) * 0.15f;
        g.enableScissor(pvX + 1, areaTop, pvX + pvW - 1, pvY + pvH - 1);
        Item con = previewContrail();
        MiniAirship.Tail tail = MiniAirship.render(g, cx, cy, scale, shipYaw, bob);
        if (con != null && con.fx() != null) {
            float speed = tail.pxPerBlock() * 2.4f;              // the wake stretches out behind the ship
            particles.emit(con.fx(), tail.x(), tail.y(), dt, tail.dx() * speed, tail.dy() * speed, tail.pxPerBlock() * 1.5f, 2.6f, 2.5f);
        }
        particles.update(dt);
        particles.render(g);
        g.disableScissor();
        if (con == null) g.drawCenteredString(font, "Pick a contrail to see it here", (int) cx, pvY + pvH - 14, 0xFF2A3A4A);
    }

    private Item previewTrail() {
        Item s = selected();
        if (s != null && s.cat() == Cat.TRAILS) return s;
        return data.byId(data.eqTrail);
    }

    private Item previewContrail() {
        Item s = selected();
        if (s != null && s.cat() == Cat.CONTRAILS) return s;
        return data.byId(data.eqContrail);
    }

    /** Badge + name, coloured per letter by the selected colour/gradient (or what's worn). */
    private Component nameplate() {
        Item s = selected();
        int[] rgb = data.nameRgb;
        boolean bold = data.eqBold;
        if (s != null && (s.cat() == Cat.COLOURS || s.cat() == Cat.GRADIENTS)) rgb = s.nameRgb();
        if (s != null && s.id().equals("bold")) bold = true;
        MutableComponent out = Component.empty();
        if (!data.rankBadge.isEmpty()) out.append(Component.literal(data.rankBadge + " ").withStyle(Style.EMPTY.withColor(0xF5C451)));
        out.append(styledName(data.name, rgb, bold));
        return out;
    }

    static MutableComponent styledName(String name, int[] rgb, boolean bold) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < name.length(); i++) {
            int c = rgb.length == 0 ? 0xFFFFFF : rgb[Math.min(i, rgb.length - 1)];
            out.append(Component.literal(String.valueOf(name.charAt(i)))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(c)).withBold(bold)));
        }
        return out;
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        List<Item> rows = rows();
        g.enableScissor(rx + 1, listY, rx + rw - 1, listY + listH);
        int y0 = listY - (int) scroll;
        for (int i = 0; i < rows.size(); i++) {
            int y = y0 + i * ROW_H;
            if (y + ROW_H < listY || y > listY + listH) continue;
            Item it = rows.get(i);
            boolean sel = it.id().equals(selectedId);
            boolean hov = mouseX >= rx && mouseX < rx + rw && mouseY >= Math.max(y, listY) && mouseY < Math.min(y + ROW_H, listY + listH);
            if (sel) g.fill(rx + 2, y + 1, rx + rw - 2, y + ROW_H - 1, 0x50F0C05A);
            else if (hov) g.fill(rx + 2, y + 1, rx + rw - 2, y + ROW_H - 1, 0x22FFFFFF);
            g.renderItem(icon(it.icon()), rx + 6, y + 3);
            Component label = (it.cat() == Cat.COLOURS || it.cat() == Cat.GRADIENTS)
                ? styledName(data.name, it.nameRgb(), false)
                : Component.literal(it.label());
            g.drawString(font, label, rx + 28, y + 3, TEXT, false);
            if (it.cat() == Cat.COLOURS || it.cat() == Cat.GRADIENTS) g.drawString(font, it.label(), rx + 28, y + 12, MUTED, false);
            else if (it.blurb() != null) g.drawString(font, ellipsize(it.blurb(), rw - 110), rx + 28, y + 12, MUTED, false);
            String chip; int col;
            if (it.equipped()) { chip = "★ Wearing"; col = GOLD; }
            else if (it.owned()) { chip = "Yours"; col = GREEN; }
            else if (it.available()) { chip = "Rank ✓"; col = GREEN; }
            else if (it.forSale()) { chip = it.price() + " ☕"; col = GOLD; }
            else if (it.rank() != null) { chip = it.rank(); col = PURPLE; }
            else { chip = "Event"; col = PURPLE; }
            g.drawString(font, chip, rx + rw - 8 - font.width(chip), y + 7, col, false);
        }
        g.disableScissor();
        if (rows.isEmpty()) g.drawCenteredString(font, "Nothing here yet", rx + rw / 2, listY + 20, MUTED);
        int total = rows.size() * ROW_H;
        if (total > listH) {                                   // scrollbar
            int barH = Math.max(12, listH * listH / total);
            int barY = listY + (int) ((listH - barH) * (scroll / Math.max(1, total - listH)));
            g.fill(rx + rw - 3, barY, rx + rw - 1, barY + barH, BRASS);
        }
    }

    private void renderDetail(GuiGraphics g) {
        Item it = selected();
        if (it == null) {
            g.drawString(font, "Pick something on the list to preview it.", rx + 8, detY + 8, MUTED, false);
            return;
        }
        g.drawString(font, Component.literal(it.label()).withStyle(Style.EMPTY.withBold(true)), rx + 8, detY + 6, TEXT, false);
        String blurb = it.blurb() != null ? it.blurb() : (it.cat() == Cat.COLOURS || it.cat() == Cat.GRADIENTS
            ? "Your name in this style, in chat, the tab list and above your head." : "");
        List<FormattedCharSequence> lines = font.split(Component.literal(blurb), rw - 16);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), rx + 8, detY + 17 + i * 9, MUTED, false);
    }

    private void renderConfirm(GuiGraphics g, int mouseX, int mouseY, float pt) {
        Item it = data.byId(confirmId);
        if (it == null) return;
        g.fill(0, 0, width, height, 0x90000000);
        int cw = 220, ch = 84, cx = width / 2 - cw / 2, cy = height / 2 - ch / 2;
        panel(g, cx, cy, cw, ch);
        g.drawCenteredString(font, Component.literal("Buy " + it.label() + "?").withStyle(Style.EMPTY.withBold(true)), width / 2, cy + 10, TEXT);
        String cost = it.price() + " Beans";
        String after = data.balance < 0 ? "" : "You'll have " + Math.max(0, data.balance - it.price()) + " left.";
        g.drawCenteredString(font, cost, width / 2, cy + 24, GOLD);
        g.drawCenteredString(font, data.balance >= 0 && data.balance < it.price() ? "Not enough Beans." : after, width / 2, cy + 36, MUTED);
        for (var w : this.renderables) w.render(g, mouseX, mouseY, pt);   // the dialog's own buttons on top
    }

    private ItemStack icon(String id) {
        return iconCache.computeIfAbsent(id, k -> {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(k));
            return new ItemStack(item == null || item == Items.AIR ? Items.PAPER : item);
        });
    }

    private String ellipsize(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        while (s.length() > 1 && font.width(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
