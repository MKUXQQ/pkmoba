package com.pkmoba.client;

import com.pkmoba.network.RoomListPayload;
import com.pkmoba.pokemon.SpeciesRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Single acquisition/roster grid. Selection is acknowledged by the server; stars reorder only on reopen. */
public final class RoomTeamScreen extends Screen {
    private RoomListPayload.RoomView room;
    private final List<String> order;
    private EditBox search;
    private String query = "";
    private int filter, scroll, pendingTicks;
    private boolean dragging;
    private String notice = "";
    public RoomTeamScreen(RoomListPayload.RoomView room) {
        super(Component.literal("选择宝可梦"));
        this.room = room;
        Set<String> stars = stars();
        order = new ArrayList<>(SpeciesRegistry.ids());
        order.sort(Comparator.comparingInt(id -> stars.contains(id) ? 0 : 1));
    }
    int roomId() { return room.id(); }
    void update(RoomListPayload.RoomView updated) { room = updated; pendingTicks = 0; clamp(); }
    void refreshFromLatest() { var current = ClientRooms.currentRoom(room.id()); if (current != null) update(current); }
    private Set<String> stars() { var snapshot = ClientRooms.latest(); return snapshot == null ? Set.of() : new HashSet<>(snapshot.favorites()); }
    private List<String> roster() { return ClientRooms.isBlue(room) ? room.blueRoster() : room.redRoster(); }
    private boolean editable() { return room.state() <= 1 && pendingTicks == 0; }
    private int pw() { return Math.min(520, width - 16); }
    private int ph() { return Math.min(292, height - 12); }
    private int left() { return (width - pw()) / 2; }
    private int top() { return (height - ph()) / 2; }
    private int gridY() { return top() + 76; }
    private int bottom() { return top() + ph() - 43; }
    private int columns() { return Math.max(1, (pw() - 36) / 84); }
    private int cw() { return (pw() - 36) / columns(); }
    private int rowHeight() { return 76; }
    private List<String> visible() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        Set<String> stars = stars();
        return order.stream().filter(id -> filter != 1 || stars.contains(id)).filter(id -> filter != 2 || roster().contains(id))
                .filter(id -> q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q) || name(id).contains(q)).toList();
    }
    private int maxScroll() { return Math.max(0, ((visible().size() + columns() - 1) / columns()) * rowHeight() - Math.max(1, bottom() - gridY())); }
    private void clamp() { scroll = Math.max(0, Math.min(scroll, maxScroll())); }
    private String name(String id) { return SpeciesRegistry.displayName(ResourceLocation.tryParse(id)); }
    private boolean hit(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x+w && my >= y && my < y+h; }
    /** Do not run the vanilla full-frame blur over custom menu artwork. */
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {}
    @Override protected void init() {
        search = new EditBox(font, left()+12, top()+29, pw()-24, 18, Component.literal("搜索宝可梦")) {
            @Override public void renderWidget(GuiGraphics g, int mx, int my, float delta) {
                PixelUi.frame(g, getX(), getY(), getWidth(), getHeight(), PixelUi.CREAM, isFocused() ? PixelUi.GREEN : PixelUi.INK);
                String value = getValue().isEmpty() && !isFocused() ? "输入中文或英文名称搜索" : getValue() + (isFocused() ? "_" : "");
                g.drawString(font, PixelUi.fit(font, value, getWidth()-10), getX()+5, getY()+5, PixelUi.TEXT, false);
            }
        };
        search.setMaxLength(64); search.setValue(query);
        search.setHint(Component.literal("输入中文名称或英文名称搜索"));
        search.setResponder(value -> { query=value; scroll=0; }); addRenderableWidget(search);
    }
    @Override public void tick() { if (pendingTicks > 0) pendingTicks--; }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0,0,width,height,0xB0252A20);
        int x=left(), y=top(), w=pw();
        PixelUi.panel(g,x,y,w,ph(),PixelUi.PAPER);
        g.drawString(font,"选择宝可梦 · 已选 " + roster().size()+"/"+room.capacity(),x+12,y+12,PixelUi.TEXT,false);
        String[] labels={"全部","仅收藏","本场已选"};
        for(int i=0;i<3;i++) PixelUi.button(g,font,x+12+i*78,y+52,74,18,labels[i],filter==i,true);
        clamp(); var items=visible(); Set<String> stars=stars();
        g.enableScissor(x+10,gridY(),x+w-20,bottom());
        int start=(scroll/rowHeight())*columns();
        for(int i=start;i<items.size();i++) {
            int cy=gridY()+(i/columns())*rowHeight()-scroll;
            if(cy>=bottom()) break;
            int cx=x+12+(i%columns())*cw(); String id=items.get(i); boolean chosen=roster().contains(id);
            PixelUi.frame(g,cx,cy,cw()-5,rowHeight()-5,chosen?PixelUi.GRASS:PixelUi.CREAM,chosen?PixelUi.GREEN:PixelUi.SHADOW);
            var entry=SpeciesRegistry.byId(ResourceLocation.tryParse(id));
            if(entry!=null) g.blit(ResourceLocation.fromNamespaceAndPath("pkmoba",entry.portrait()),cx+(cw()-45)/2,cy+4,40,40,0F,0F,64,64,64,64);
            if(chosen) g.drawString(font,"✓",cx+4,cy+4,PixelUi.GREEN,false);
            if(stars.contains(id)) g.drawString(font,"★",cx+cw()-19,cy+4,0xFFAD8025,false);
            String text=PixelUi.fit(font,name(id),cw()-13);
            g.drawString(font,text,cx+(cw()-5-font.width(text))/2,cy+49,PixelUi.TEXT,false);
            if(chosen) g.drawString(font,"已选",cx+5,cy+60,PixelUi.GREEN,false);
        }
        g.disableScissor();
        int track=bottom()-gridY(); g.fill(x+w-16,gridY(),x+w-11,bottom(),PixelUi.CREAM);
        if(maxScroll()>0) { int thumb=Math.max(12,track*track/(track+maxScroll())); int ty=gridY()+(track-thumb)*scroll/maxScroll(); g.fill(x+w-16,ty,x+w-11,ty+thumb,PixelUi.GREEN); }
        String hint = pendingTicks>0?"等待服务端确认…":notice.isEmpty()?"左键选中/取消 · 右键收藏 · 下次打开收藏靠前":notice;
        g.drawString(font,PixelUi.fit(font,hint,w-24),x+12,bottom()+4,PixelUi.MUTED,false);
        PixelUi.button(g,font,x+12,bottom()+18,w-24,20,"返回房间",hit(mx,my,x+12,bottom()+18,w-24,20),true);
        super.render(g,mx,my,delta);
    }
    @Override public boolean mouseClicked(double mx,double my,int button) {
        int x=left(), y=top();
        if(button==0 && hit(mx,my,x+12,bottom()+18,pw()-24,20)) { onClose(); return true; }
        for(int i=0;i<3;i++) if(button==0 && hit(mx,my,x+12+i*78,y+52,74,18)) { filter=i;scroll=0;return true; }
        if(button==0 && hit(mx,my,x+pw()-18,gridY(),10,bottom()-gridY())) { dragging=true; dragScroll(my); return true; }
        if((button==0||button==1)&&hit(mx,my,x+12,gridY(),pw()-36,bottom()-gridY())) {
            int col=((int)mx-x-12)/cw(), row=((int)my-gridY()+scroll)/rowHeight(), index=row*columns()+col;
            var items=visible();
            if(col<columns()&&index>=0&&index<items.size()&&editable()) {
                String id=items.get(index);
                if(button==0&&!roster().contains(id)&&roster().size()>=room.capacity()) notice="已达上限，请先取消一只";
                else { ClientRooms.send(button==1?"FAVORITE":"TOGGLE_ROSTER",room.id(),id); pendingTicks=60;notice=""; }
            }
            return true;
        }
        return super.mouseClicked(mx,my,button);
    }
    private void dragScroll(double my) { int range=Math.max(1,bottom()-gridY()); scroll=(int)((my-gridY())/range*maxScroll()); clamp(); }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy) { if(dragging) { dragScroll(my);return true; }return super.mouseDragged(mx,my,button,dx,dy); }
    @Override public boolean mouseReleased(double mx,double my,int button) { dragging=false;return super.mouseReleased(mx,my,button); }
    @Override public boolean mouseScrolled(double mx,double my,double sx,double sy) { if(hit(mx,my,left(),gridY(),pw(),bottom()-gridY())) {scroll-=(int)(sy*28);clamp();return true;}return super.mouseScrolled(mx,my,sx,sy); }
    @Override public void onClose() { ClientRooms.returnToRoom(room.id()); }
    @Override public boolean isPauseScreen() { return false; }
}
