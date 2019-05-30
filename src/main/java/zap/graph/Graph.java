package zap.graph;

import gnu.trove.map.hash.TObjectByteHashMap;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import zap.graph.traverse.BFSearcher;
import zap.graph.traverse.INodeContainer;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class Graph<C, E> implements INodeContainer {
	private HashMap<BlockPos, UUID> posGrouping;
	HashMap<UUID, Group<C, E>> groups;
	
	public Graph() {
		posGrouping = new HashMap<>();
		groups = new HashMap<>();
	}

	@Override
	public boolean contains(BlockPos pos) {
		return posGrouping.containsKey(pos);
	}

	@SuppressWarnings("unchecked")
	public void addEndpoint(BlockPos pos, E endpoint) {
		ArrayList<UUID> mergers = getNeighboringGroups(pos);

		if(mergers.size()==0) {
			UUID uuid = getNewId();

			posGrouping.put(pos, uuid);
			groups.put(uuid, (Group<C, E>)Group.singleEndpoint(pos, endpoint));
		} else if(mergers.size()==1) {
			UUID uuid = mergers.get(0);

			posGrouping.put(pos, uuid);
			groups.get(uuid).addEndpoint(pos, endpoint);
		} else {
			MergeData<C, E> data = beginMerge(mergers);

			posGrouping.put(pos, data.bestId);
			data.best.addMergingEndpoint(pos, endpoint, data.mergeGroups);
		}
	}

	@SuppressWarnings("unchecked")
	public void addCable(BlockPos pos, C cable) {
		ArrayList<UUID> mergers = getNeighboringGroups(pos);

		if(mergers.size()==0) {
			UUID uuid = getNewId();

			posGrouping.put(pos, uuid);
			groups.put(uuid, (Group<C, E>)Group.singleCable(pos, cable));
		} else if(mergers.size()==1) {
			UUID uuid = mergers.get(0);

			posGrouping.put(pos, uuid);
			groups.get(uuid).addCable(pos, cable);
		} else {
			MergeData<C, E> data = beginMerge(mergers);

			posGrouping.put(pos, data.bestId);
			data.best.addMergingCable(pos, cable, data.mergeGroups);
		}
	}

	@Nullable
	public Entry<C, E> remove(BlockPos pos) {
		UUID uuid = posGrouping.remove(pos);
		Group<C, E> group = groups.get(uuid);

		// Test 1: If this a single-entry group, just remove it outright.
		if(group.countBlocks() <= 1) {
			groups.remove(uuid);
			return group.remove(pos);
		}

		// Test 2: If the particular entry is on the very edge of the group, then remove it from the group.
		int neighbors = 0;
		for(EnumFacing facing: EnumFacing.VALUES) {
			BlockPos face = pos.offset(facing);

			if(group.contains(face)) {
				neighbors += 1;
			}
		}

		Entry<C, E> entry = group.remove(pos);
		if(neighbors <= 1) {
			return entry;
		}

		// Finally, if none of the fast routes work, we need to due a full group-traversal to figure out how the graph will be split.
		// The algorithm works by "coloring" each fragment of the group based on what it is connected to, and then from this,
		// splitting each colored portion into its own separate group.
		// For optimization purposes, the largest colored fragment remains resident within its original group.
		
		BFSearcher searcher = new BFSearcher(group);
		TObjectByteHashMap<BlockPos> colors = new TObjectByteHashMap<BlockPos>(group.countBlocks(), 0.5F, (byte)127);
		byte[] facingToColor = new byte[] { 127, 127, 127, 127, 127, 127 };
		int[] counts = new int[6];
		byte color = 0;

		for(EnumFacing facing: EnumFacing.VALUES) {
			BlockPos side = pos.offset(facing);

			if(facingToColor[facing.ordinal()] != 127) {
				// Already colored!
				continue;
			} else if(!group.contains(side)) {
				// Can't start from here.
				continue;
			}

			facingToColor[facing.ordinal()] = color;

			System.out.println("Searching on side "+facing);
			final byte targetColor = color;
			searcher.search(side, reached -> {
				colors.put(reached, targetColor);
				counts[targetColor]++;
			});

			color++;
		}

		int best = 0;
		int bestCount = 0;
		for(int i = 0; i < color; i++) {
			int count = counts[i];

			if(count > bestCount) {
				bestCount = count;
				best = i;
			}

			System.out.println("Count: "+ count);
		}

		final int bestColor = best;

		ArrayList<Group<C, E>> newGroups = new ArrayList<>();
		ArrayList<UUID> newUuids = new ArrayList<>();
		for(int i = 0; i < color; i++) {
			if(i == bestColor) {
				newGroups.add(null);
				newUuids.add(null);
				continue;
			}

			Group<C, E> newGroup = new Group<>();
			UUID newUUID = getNewId();

			groups.put(newUUID, newGroup);

			newGroups.add(newGroup);
			newUuids.add(newUUID);
		}

		colors.forEachEntry((reached, reachedColor) -> {
			if(reachedColor == 127) {
				throw new IllegalStateException("Graph::remove: Node at "+reached+" was not connected to the group it was assigned to, the graph is inconsistent");
			}

			if(reachedColor != bestColor) {
				Group<C,E> target = newGroups.get(reachedColor);
				UUID targetUuid = newUuids.get(reachedColor);

				Entry<C, E> targetEntry = group.remove(reached);
				Objects.requireNonNull(targetEntry, "Graph::remove: Null entry when draining group");

				posGrouping.put(reached, targetUuid);
				target.addEntry(reached, targetEntry);
			}

			return true;
		});

		return entry;
	}

	@SuppressWarnings("unchecked")
	private MergeData<C, E> beginMerge(ArrayList<UUID> mergers) {
		UUID bestId = mergers.get(0);
		Group<C, E> best = groups.get(bestId);
		int bestSize = best.countBlocks();

		for(UUID id: mergers) {
			Group<C, E> candidate = groups.get(id);
			int size = candidate.countBlocks();

			if(size > bestSize) {
				best = candidate;
				bestId = id;
				bestSize = size;
			}
		}

		Group<C, E>[] mergeGroups = new Group[mergers.size() - 1];
		int i = 0;
		for(UUID id: mergers) {
			if(id.equals(bestId)) {
				continue;
			}

			for(Map.Entry<BlockPos, UUID> posGroup: posGrouping.entrySet()) {
				if(posGroup.getValue().equals(id)) {
					posGroup.setValue(bestId);
				}
			}

			mergeGroups[i++] = groups.remove(id);
		}

		MergeData<C, E> data = new MergeData<>();

		data.best = best;
		data.bestId = bestId;
		data.mergeGroups = mergeGroups;

		return data;
	}

	private ArrayList<UUID> getNeighboringGroups(BlockPos pos) {
		ArrayList<UUID> neighbors = new ArrayList<>(6);

		for(EnumFacing facing: EnumFacing.VALUES) {
			BlockPos face = pos.offset(facing);
			UUID group = posGrouping.get(face);

			if(group == null) {
				continue;
			}

			if(!neighbors.contains(group)) {
				neighbors.add(group);
			}
		}

		return neighbors;
	}

	private UUID getNewId() {
		UUID uuid = UUID.randomUUID();
		while(groups.containsKey(uuid)) {
			// Should never be called, but whatever.
			uuid = UUID.randomUUID();
		}

		return uuid;
	}

	// Wish Java had tuples...
	private static class MergeData<C, E> {
		Group<C, E> best;
		UUID bestId;
		Group<C, E>[] mergeGroups;
	}
}
