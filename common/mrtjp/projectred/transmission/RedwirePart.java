package mrtjp.projectred.transmission;

import mrtjp.projectred.core.BasicUtils;
import mrtjp.projectred.core.Configurator;
import mrtjp.projectred.core.CoreProxy;
import mrtjp.projectred.core.Messenger;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatMessageComponent;
import codechicken.lib.data.MCDataInput;
import codechicken.lib.data.MCDataOutput;
import codechicken.lib.packet.PacketCustom;
import codechicken.lib.vec.BlockCoord;
import codechicken.lib.vec.Rotation;
import codechicken.multipart.IFaceRedstonePart;
import codechicken.multipart.IRedstonePart;
import codechicken.multipart.RedstoneInteractions;
import codechicken.multipart.TMultiPart;
import codechicken.multipart.TileMultipart;
import codechicken.multipart.scalatraits.TRedstoneTile;

public abstract class RedwirePart extends WirePart implements IRedwirePart, IRedwireEmitter, IFaceRedstonePart {

    public byte signal;
    
    public RedwirePart(int side) {
        super(side);
    }
    
    @Override
    public void save(NBTTagCompound tag) {
        super.save(tag);
        tag.setByte("signal", signal);
    }
    
    @Override
    public void load(NBTTagCompound tag) {
        super.load(tag);
        signal = tag.getByte("signal");
    }
    
    @Override
    public void writeDesc(MCDataOutput packet) {
        super.writeDesc(packet);
        packet.writeByte(signal);
    }
    
    @Override
    public void readDesc(MCDataInput packet) {
        super.readDesc(packet);
        signal = packet.readByte();
    }
    
    @Override
    public void read(MCDataInput packet, int switch_key) {
        if(switch_key == 10) {
            signal = packet.readByte();
            tile().markRender();
        }
        else
            super.read(packet, switch_key);
    }
    
    @Override
    public int strongPowerLevel(int side) {
        return 0;
    }
    
    @Override
    public int weakPowerLevel(int side) {
        return rsLevel();
    }
    
    @Override
    public boolean canConnectRedstone(int side) {
        return WirePropogator.redwiresConnectable();
    }
    
    public int rsLevel() {
        if(WirePropogator.redwiresProvidePower)
            return ((signal&0xFF)+16)/17;
        
        return 0;
    }
    
    @Override
    public int getFace() {
        return side;
    }

    public boolean connectionOpen(int r) {
        int absDir = Rotation.rotateSide(side, r);
        return (((TRedstoneTile)tile()).openConnections(absDir) & 1<<Rotation.rotationTo(absDir&6, side)) != 0;
    }
    
    @Override
    public boolean canConnectToType(IWirePart wire) {
        return wire instanceof IRedwirePart;
    }

    @Override
    public boolean connectStraightOverride(int absDir) {
        return (RedstoneInteractions.otherConnectionMask(world(), x(), y(), z(), absDir, false) & 
                RedstoneInteractions.connectionMask(this, absDir)) != 0;
    }

    @Override
    public boolean connectInternalOverride(TMultiPart p, int r) {
        if (p instanceof IRedstonePart) {
            IRedstonePart rsPart = (IRedstonePart)p;
            return rsPart.canConnectRedstone(side);
        }
        
        return false;
    }

    @Override
    public void updateAndPropogate(TMultiPart prev, int mode) {
        if(mode == DROPPING && signal == 0)
            return;
        
        int newSignal = calculateSignal();
        if(newSignal < getRedwireSignal()) {
            signal = 0;
            propogate(prev, DROPPING);
        }
        else if(newSignal > getRedwireSignal()) {
            signal = (byte)newSignal;
            if(mode == DROPPING)
                propogate(null, RISING);
            else
                propogate(prev, RISING);
        }
        else {
            if(mode == DROPPING)
                propogateTo(prev, RISING);
            else if(mode == FORCE)
                propogate(prev, FORCED);
        }
    }

    public void onSignalUpdate() {
        super.onSignalUpdate();
        tile().getWriteStream(this).writeByte(10).writeByte(signal);
    }
    
    public int calculateSignal() {
        WirePropogator.setWiresProvidePower(false);
        WirePropogator.redwiresProvidePower = false;
        
        int s = 0;
        int i;
        for(int r = 0; r < 4; r++)
            if(maskConnects(r)) {
                if((connMap & 1<<r) != 0)
                    i = calculateCornerSignal(r);
                else if((connMap & 0x10<<r) != 0)
                    i = calculateStraightSignal(r);
                else
                    i = calculateInternalSignal(r);
                
                if(i > s)
                    s = i;
            }
        
        i = calculateUndersideSignal();
        if(i > s)
            s = i;
        
        if((connMap & 0x10000) != 0) {
            i = calculateCenterSignal();
            if(i > s)
                s = i;
        }
        
        WirePropogator.setWiresProvidePower(true);
        WirePropogator.redwiresProvidePower = true;
        
        return s;
    }

    public int calculateCornerSignal(int r) {
        int absDir = Rotation.rotateSide(side, r);
        
        BlockCoord pos = new BlockCoord(getTile()).offset(absDir).offset(side);
        TileMultipart t = BasicUtils.getMultipartTile(world(), pos);
        if (t != null)
            return getPartSignal(t.partMap(absDir^1), Rotation.rotationTo(absDir^1, side^1));
        
        return 0;
    }

    public int calculateStraightSignal(int r) {
        int absDir = Rotation.rotateSide(side, r);
        
        BlockCoord pos = new BlockCoord(getTile()).offset(absDir);
        TileMultipart t = BasicUtils.getMultipartTile(world(), pos);
        if (t != null) {
            int i = getPartSignal(t.partMap(side), (r+2)%4);
            if(i > 0)
                return i;
        }
    
        int blockID = world().getBlockId(pos.x, pos.y, pos.z);
        if(blockID == Block.redstoneWire.blockID)
            return world().getBlockMetadata(pos.x, pos.y, pos.z)-1;
        
        return RedstoneInteractions.getPowerTo(this, absDir)*17;
    }

    public int calculateInternalSignal(int r) {
        int absDir = Rotation.rotateSide(side, r);
        
        TMultiPart tp = tile().partMap(absDir);
        int i = getPartSignal(tp, Rotation.rotationTo(absDir, side));
        if(i > 0)
            return i;
        
        if(tp instanceof IRedstonePart) {
            IRedstonePart rp = (IRedstonePart) tp;
            return Math.max(rp.strongPowerLevel(side), rp.weakPowerLevel(side)) << 4;
        }
        
        return 0;
    }

    public int calculateCenterSignal() {
        return getPartSignal(tile().partMap(6), side);
    }

    public int calculateUndersideSignal() {
        return 0;
    }

    public int getPartSignal(TMultiPart part, int r) {
        if(part instanceof IRedwirePart)
            return ((IRedwirePart) part).getRedwireSignal() - 1;
        else if(part instanceof IRedwireEmitter)
            return ((IRedwireEmitter) part).getRedwireSignal(r);
        
        return 0;
    }

    @Override
    public int getRedwireSignal() {
        return signal & 0xFF;
    }
    
    @Override
    public int getRedwireSignal(int side) {
        return getRedwireSignal();
    }

    @Override
    protected boolean debug(EntityPlayer ply) {
        ply.sendChatToPlayer(ChatMessageComponent.func_111077_e((world().isRemote ? "Client" : "Server") + " signal strength: " + getRedwireSignal()));
        return true;
    }
    
    @Override
    protected boolean test(EntityPlayer player) {
        if (BasicUtils.isClient(world())) {
            Messenger.addMessage(x() + 0, y() + .5f, z() + 0,  "/#f/#c[c] = " + getRedwireSignal());
        } else {
            PacketCustom packet = new PacketCustom(Configurator.corePacketChannel, CoreProxy.messengerQueue);
            packet.writeDouble(x() + 0.0D);
            packet.writeDouble(y() + 0.5D);
            packet.writeDouble(z() + 0.0D);
            packet.writeString("/#c[s] = " + getRedwireSignal());
            packet.sendToPlayer(player);
        }
        return true;
    }
}
