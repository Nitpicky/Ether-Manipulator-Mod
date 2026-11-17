package com.ethermod.client;

import com.ethermod.entity.SoulBlazeling;

import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;

public final class BlazelingModel extends HierarchicalModel<SoulBlazeling> {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                            "ethermod", "soul_blazeling"),
                    "main");
    private final ModelPart root, head;
    private final ModelPart[] rods = new ModelPart[3];

    public BlazelingModel(ModelPart root) {
        this.root = root;
        head = root.getChild("head");
        for (int i = 0; i < 3; i++) rods[i] = root.getChild("rod_" + i);
    }

    public static LayerDefinition layer() {
        var mesh = new MeshDefinition();
        var root = mesh.getRoot();
        root.addOrReplaceChild(
                "head",
                CubeListBuilder.create().texOffs(0, 0).addBox(-3, -3, -3, 6, 6, 6),
                PartPose.offset(0, 20, 0));
        for (int i = 0; i < 3; i++)
            root.addOrReplaceChild(
                    "rod_" + i,
                    CubeListBuilder.create().texOffs(0, 12).addBox(-.5f, -3, -.5f, 1, 6, 1),
                    PartPose.offset(0, 23, 0));
        return LayerDefinition.create(mesh, 32, 32);
    }

    public ModelPart root() {
        return root;
    }

    public void setupAnim(
            SoulBlazeling e, float limb, float amount, float age, float yaw, float pitch) {
        head.yRot = yaw * (float) Math.PI / 180;
        head.xRot = pitch * (float) Math.PI / 180;
        // Vanilla middle-ring angular speed, one ring with 120-degree spacing; retain source rod
        // radius.
        double phase = Math.atan2(-2.5, -5.5) + age * Math.PI * .03, radius = Math.sqrt(36.5);
        for (int i = 0; i < 3; i++) {
            double a = phase + i * Math.PI * 2 / 3;
            rods[i].x = (float) (Math.cos(a) * radius);
            rods[i].z = (float) (Math.sin(a) * radius);
            rods[i].y = 23 + (float) Math.cos(age * .25 + i * Math.PI * 2 / 3) * .5f;
        }
    }
}
