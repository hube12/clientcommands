package net.earthcomputer.clientcommands.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.earthcomputer.clientcommands.GuiBlocker;
import net.earthcomputer.clientcommands.IServerCommandSource;
import net.earthcomputer.clientcommands.MathUtil;
import net.earthcomputer.clientcommands.task.LongTask;
import net.earthcomputer.clientcommands.task.TaskManager;
import net.minecraft.ChatFormat;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ContainerProvider;
import net.minecraft.command.arguments.ItemStackArgument;
import net.minecraft.command.arguments.ItemStackArgumentType;
import net.minecraft.container.Container;
import net.minecraft.container.Slot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.*;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.DefaultedList;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.earthcomputer.clientcommands.command.ClientCommandManager.*;
import static net.minecraft.command.arguments.ItemStackArgumentType.*;
import static net.minecraft.server.command.CommandManager.*;

public class FindItemCommand {

    private static final int FLAG_NO_SEARCH_SHULKER_BOX = 1;
    private static final int FLAG_KEEP_SEARCHING = 2;

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        addClientSideCommand("cfinditem");

        LiteralCommandNode<ServerCommandSource> cfinditem = dispatcher.register(literal("cfinditem"));
        dispatcher.register(literal("cfinditem")
            .then(literal("--no-search-shulker-box")
                .redirect(cfinditem, ctx -> ctx.getSource().withLevel(((IServerCommandSource) ctx.getSource()).getLevel() | FLAG_NO_SEARCH_SHULKER_BOX)))
            .then(literal("--keep-searching")
                .redirect(cfinditem, ctx -> ctx.getSource().withLevel(((IServerCommandSource) ctx.getSource()).getLevel() | FLAG_KEEP_SEARCHING)))
            .then(argument("item", ItemStackArgumentType.create())
                .executes(ctx ->
                        findItem(ctx.getSource(),
                                (((IServerCommandSource) ctx.getSource()).getLevel() & FLAG_NO_SEARCH_SHULKER_BOX) != 0,
                                (((IServerCommandSource) ctx.getSource()).getLevel() & FLAG_KEEP_SEARCHING) != 0,
                                getItemStackArgument(ctx, "item")))));
    }

    private static int findItem(ServerCommandSource source, boolean noSearchShulkerBox, boolean keepSearching, ItemStackArgument item) {
        String taskName = TaskManager.addTask("cfinditem", new FindItemsTask(item, !noSearchShulkerBox, keepSearching));
        if (keepSearching) {
            Component cancel = new TranslatableComponent("commands.cfinditem.starting.cancel");
            cancel.getStyle().setUnderline(true);
            cancel.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ctask stop " + taskName));
            cancel.getStyle().setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponent("/ctask stop " + taskName)));
            sendFeedback(new TranslatableComponent("commands.cfinditem.starting.keepSearching", Registry.ITEM.getId(item.getItem()))
                    .append(" ")
                    .append(cancel));
        } else {
            sendFeedback(new TranslatableComponent("commands.cfinditem.starting", Registry.ITEM.getId(item.getItem())));
        }

        return 0;
    }

    private static class FindItemsTask extends LongTask {
        private final ItemStackArgument searchingFor;
        private final boolean searchShulkerBoxes;
        private final boolean keepSearching;

        private int totalFound = 0;
        private Set<BlockPos> searchedBlocks = new HashSet<>();
        private BlockPos currentlySearching = null;
        private int currentlySearchingTimeout;

        public FindItemsTask(ItemStackArgument searchingFor, boolean searchShulkerBoxes, boolean keepSearching) {
            this.searchingFor = searchingFor;
            this.searchShulkerBoxes = searchShulkerBoxes;
            this.keepSearching = keepSearching;
        }

        @Override
        public void initialize() {
        }

        @Override
        public boolean condition() {
            return true;
        }

        @Override
        public void increment() {
        }

        @Override
        public void body() {
            World world = MinecraftClient.getInstance().world;
            Entity entity = MinecraftClient.getInstance().cameraEntity;
            if (entity == null) {
                _break();
                return;
            }
            if (currentlySearchingTimeout > 0) {
                currentlySearchingTimeout--;
                scheduleDelay();
                return;
            }
            if (MinecraftClient.getInstance().player.isSneaking()) {
                scheduleDelay();
                return;
            }
            Vec3d origin = entity.getCameraPosVec(0);
            float reachDistance = MinecraftClient.getInstance().interactionManager.getReachDistance();
            int minX = MathHelper.floor(origin.x - reachDistance);
            int minY = MathHelper.floor(origin.y - reachDistance);
            int minZ = MathHelper.floor(origin.z - reachDistance);
            int maxX = MathHelper.floor(origin.x + reachDistance);
            int maxY = MathHelper.floor(origin.y + reachDistance);
            int maxZ = MathHelper.floor(origin.z + reachDistance);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!canSearch(world, pos))
                            continue;
                        if (searchedBlocks.contains(pos))
                            continue;
                        Vec3d closestPos = MathUtil.getClosestPoint(pos, world.getBlockState(pos).getOutlineShape(world, pos), origin);
                        if (closestPos.squaredDistanceTo(origin) > reachDistance * reachDistance)
                            continue;
                        searchedBlocks.add(pos);
                        BlockState state = world.getBlockState(pos);
                        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                            BlockPos offsetPos = pos.offset(ChestBlock.getFacing(state));
                            if (world.getBlockState(offsetPos).getBlock() == state.getBlock())
                                searchedBlocks.add(offsetPos);
                        }
                        startSearch(world, pos, origin, closestPos);
                        scheduleDelay();
                        return;
                    }
                }
            }
            if (!keepSearching)
                _break();
            else
                scheduleDelay();
        }

        private boolean canSearch(World world, BlockPos pos) {
            BlockState state = world.getBlockState(pos);
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof Inventory) && state.getBlock() != Blocks.ENDER_CHEST)
                return false;
            if (state.getBlock() instanceof ChestBlock || state.getBlock() == Blocks.ENDER_CHEST) {
                if (isChestBlocked(world, pos))
                    return false;
                if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                    BlockPos offsetPos = pos.offset(ChestBlock.getFacing(state));
                    if (world.getBlockState(offsetPos).getBlock() == state.getBlock() && isChestBlocked(world, offsetPos))
                        return false;
                }
            }
            return true;
        }

        private static boolean isChestBlocked(World world, BlockPos pos) {
            if (world.getBlockState(pos.up()).isSimpleFullBlock(world, pos.up()))
                return true;
            List<CatEntity> cats = world.getEntities(CatEntity.class, new BoundingBox(pos.getX(), pos.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1));
            for (CatEntity cat : cats) {
                if (cat.isSitting())
                    return true;
            }
            return false;
        }

        private void startSearch(World world, BlockPos pos, Vec3d cameraPos, Vec3d clickPos) {
            MinecraftClient mc = MinecraftClient.getInstance();
            currentlySearching = pos;
            currentlySearchingTimeout = 100;
            GuiBlocker.addBlocker(new GuiBlocker() {
                @Override
                public boolean accept(Screen screen) {
                    if (!(screen instanceof ContainerProvider))
                        return true;
                    Container container = ((ContainerProvider) screen).getContainer();
                    Set<Integer> playerInvSlots = new HashSet<>();
                    for (Slot slot : container.slotList)
                        if (slot.inventory instanceof PlayerInventory)
                            playerInvSlots.add(slot.id);
                    MinecraftClient.getInstance().player.container = new Container(container.getType(), container.syncId) {
                        @Override
                        public boolean canUse(PlayerEntity var1) {
                            return true;
                        }

                        @Override
                        public void updateSlotStacks(List<ItemStack> stacks) {
                            int matchingItems = 0;
                            for (int slot = 0; slot < stacks.size(); slot++) {
                                if (playerInvSlots.contains(slot))
                                    continue;
                                ItemStack stack = stacks.get(slot);
                                if (searchingFor.test(stack))
                                    matchingItems += stack.getCount();
                                if (searchShulkerBoxes && stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock) {
                                    CompoundTag blockEntityTag = stack.getSubTag("BlockEntityTag");
                                    if (blockEntityTag != null && blockEntityTag.containsKey("Items")) {
                                        DefaultedList<ItemStack> boxInv = DefaultedList.create(27, ItemStack.EMPTY);
                                        Inventories.fromTag(blockEntityTag, boxInv);
                                        for (ItemStack stackInBox : boxInv) {
                                            if (searchingFor.test(stackInBox)) {
                                                matchingItems += stackInBox.getCount();
                                            }
                                        }
                                    }
                                }
                            }
                            if (matchingItems > 0) {
                                sendFeedback(new TranslatableComponent("commands.cfinditem.match.left", matchingItems, Registry.ITEM.getId(searchingFor.getItem()))
                                        .append(getCoordsTextComponent(currentlySearching))
                                        .append(new TranslatableComponent("commands.cfinditem.match.right", matchingItems, Registry.ITEM.getId(searchingFor.getItem()))));
                                totalFound += matchingItems;
                            }
                            currentlySearching = null;
                            currentlySearchingTimeout = 0;
                            MinecraftClient.getInstance().player.closeContainer();
                        }
                    };
                    return false;
                }
            });
            mc.interactionManager.interactBlock(mc.player, mc.world, Hand.MAIN_HAND,
                    new BlockHitResult(clickPos,
                            Direction.getFacing((float) (clickPos.x - cameraPos.x), (float) (clickPos.y - cameraPos.y), (float) (clickPos.z - cameraPos.z)),
                            pos, false));
        }

        @Override
        public void onCompleted() {
            sendFeedback(new TranslatableComponent("commands.cfinditem.total", totalFound, Registry.ITEM.getId(searchingFor.getItem())).applyFormat(ChatFormat.BOLD));
        }
    }
}
