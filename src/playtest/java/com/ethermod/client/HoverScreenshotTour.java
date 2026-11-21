package com.ethermod.client;

import com.ethermod.item.ManipulatorStage;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.nio.file.*;
import java.util.*;

/** Opt-in in-game hover regression: virtual render coordinates, never desktop pointer control. */
public final class HoverScreenshotTour {
    static int tick, phase;
    static int restingII, restingIII, inactiveII;
    static final List<String> report = new ArrayList<>();
    static void check(String label, boolean pass) { String line=(pass?"PASS ":"FAIL ")+label; report.add(line); System.out.println(line); }
    static Object field(Object owner, Class<?> type, String name) {
        try {var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
        catch(Exception e){throw new RuntimeException(e);}
    }
    static class Screen extends TraitScreen {
        @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float pt) {
            int[] target=switch(phase){case 1->new int[]{152,40};case 2->new int[]{196,40};case 3->new int[]{130,31};default->new int[]{-10,-10};};
            int left=(int)field(this,UpgradeScreen.class,"left"),top=(int)field(this,UpgradeScreen.class,"top");
            float scale=(float)field(this,UpgradeScreen.class,"uiScale");
            super.render(g,left+Math.round(target[0]*scale),top+Math.round(target[1]*scale),pt);
        }
    }
    static int[] pixels(Minecraft mc) {
        var panels=(Object[])field(mc.gui.screen(),UpgradeScreen.class,"panels");
        return (int[])field(panels[0],panels[0].getClass(),"finalPixels");
    }
    static int sample(int[] p,int x,int y){return p[y*146+x];}
    static void screenshot(Minecraft mc,String name){net.minecraft.client.Screenshot.grab(mc.gameDirectory,name+".png",mc.gameRenderer.mainRenderTarget(),c->{});}
    public static void step(Minecraft mc) {
        tick++;
        if(tick==10)mc.getSingleplayerServer().execute(()->{
            var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());var st=Manipulator.stack(p);
            ManipulatorStage.set(st,ManipulatorStage.NETHERITE);
            var ids=new net.minecraft.resources.Identifier[]{Traits.PLASMIC,Traits.KINETIC,Traits.HYDRAULIC};
            for(int slot=0;slot<3;slot++){
                ModuleUnlocks.unlock(st,ids[slot]);TraitBuild.setLine(st,slot,ids[slot]);
                var line=TraitLines.get(p.registryAccess(),ids[slot]).orElseThrow();
                for(int tier=0;tier<3;tier++)TraitBuild.setPick(st,slot,tier,line.tier(tier).get(tier%2).id());
            }
            for(int n=0;n<7;n++)Loadouts.handle(p,Loadouts.SAVE,0,"Set "+(n+1));Manipulator.sync(p);
        });
        if(tick==25)mc.gui.setScreen(new Screen());
        if(tick==90){
            var p=pixels(mc);restingII=sample(p,67,18);restingIII=sample(p,110,18);
            check("Choice, badge and active Common share dark fill",sample(p,42,7)==sample(p,8,11)&&sample(p,42,7)==sample(p,65,17));
            var panels=(Object[])field(mc.gui.screen(),UpgradeScreen.class,"panels");var image=(com.mojang.blaze3d.platform.NativeImage)field(panels[0],panels[0].getClass(),"image");
            boolean exact=true;for(int i=0;i<p.length;i++){int rgb=p[i],restored=image.getPixel(i%146,i/146);if(rgb!=restored){exact=false;break;}}
            check("Full RGB bytes survive panel texture upload",exact);screenshot(mc,"hover-01-rest");phase=1;
        }
        if(tick==160){var p=pixels(mc);check("Common II numeral brightens",sample(p,67,18)!=restingII);boolean intact=true;for(int y=18;y<23;y++)intact&=sample(p,67,y)==sample(p,67,18);check("Common II numeral survives hover fill",intact);screenshot(mc,"hover-02-common-II");phase=2;}
        if(tick==230){var p=pixels(mc);check("Common III numeral brightens",sample(p,110,18)!=restingIII);boolean intact=true;for(int y=18;y<23;y++)intact&=sample(p,110,y)==sample(p,110,18);check("Common III numeral survives hover fill",intact);screenshot(mc,"hover-03-common-III");phase=3;}
        if(tick==300)screenshot(mc,"hover-04-choice-A");
        if(tick==315){phase=0;mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());TraitBuild.setPick(Manipulator.stack(p),0,0,"");Manipulator.sync(p);});}
        if(tick==355){inactiveII=sample(pixels(mc),67,18);phase=1;}
        if(tick==415){var p=pixels(mc);check("Available inactive Common also brightens",sample(p,67,18)!=inactiveII);boolean intact=true;for(int y=18;y<23;y++)intact&=sample(p,67,y)==sample(p,67,18);check("Inactive Common numeral survives hover fill",intact);screenshot(mc,"hover-05-inactive-common");}
        if(tick==450){try{Files.write(Path.of("hover-probe-report.txt"),report);}catch(Exception e){throw new RuntimeException(e);}mc.stop();}
    }
}
