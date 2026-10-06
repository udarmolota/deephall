package technology.rocketjump.mountaincore.settlement;

import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.math.Vector2;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.mapping.model.TiledMap;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.misc.VectorUtils;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static technology.rocketjump.mountaincore.invasions.InvasionMessageHandler.getNavigableMapEdgeTiles;

/**
 * Where visitors from outside (traders, immigrants, invaders) step onto the map, and where the
 * way in is cut off when there is none.
 */
public final class MapEntry {

	private MapEntry() {
	}

	/**
	 * A spawn position just outside the map edge from which the settlement can be walked to, or
	 * null when the settlement is sealed off. With a preferred position the nearest such edge is
	 * used, so visitors keep coming along the same road.
	 */
	public static Vector2 findEntry(GameContext gameContext, Vector2 preferredPosition) {
		int settlementRegionId = ImmigrationManager.determineSettlementRegionId(gameContext, true);
		List<MapTile> edgeTiles = getNavigableMapEdgeTiles(settlementRegionId, gameContext);
		if (edgeTiles.isEmpty()) {
			return null;
		}

		MapTile entryTile;
		if (preferredPosition == null) {
			entryTile = edgeTiles.get(gameContext.getRandom().nextInt(edgeTiles.size()));
		} else {
			entryTile = edgeTiles.get(0);
			for (MapTile edgeTile : edgeTiles) {
				if (edgeTile.getWorldPositionOfCenter().dst2(preferredPosition) < entryTile.getWorldPositionOfCenter().dst2(preferredPosition)) {
					entryTile = edgeTile;
				}
			}
		}
		return outsideEdge(entryTile, gameContext.getAreaMap());
	}

	/**
	 * Where the way between the settlement and the map edge is cut off: the first obstacle (a
	 * wall, blocking furniture, a river) on the route to the edge that crosses the fewest of them.
	 * Null if the settlement is not sealed off or has nobody in it.
	 */
	public static Vector2 findBlockage(GameContext gameContext) {
		TiledMap map = gameContext.getAreaMap();
		int settlementRegionId = ImmigrationManager.determineSettlementRegionId(gameContext, true);
		if (!getNavigableMapEdgeTiles(settlementRegionId, gameContext).isEmpty()) {
			return null;
		}
		GridPoint2 start = findSettlementTile(gameContext, settlementRegionId);
		if (start == null) {
			return null;
		}

		// 0-1 breadth first search: walkable tiles cost nothing, every obstacle crossed costs one
		int width = map.getWidth();
		int height = map.getHeight();
		int[] cost = new int[width * height];
		int[] cameFrom = new int[width * height];
		Arrays.fill(cost, Integer.MAX_VALUE);
		Arrays.fill(cameFrom, -1);
		Deque<Integer> queue = new ArrayDeque<>();
		int startIndex = start.y * width + start.x;
		cost[startIndex] = 0;
		queue.add(startIndex);

		int bestEdge = -1;
		int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		while (!queue.isEmpty()) {
			int index = queue.pollFirst();
			int x = index % width;
			int y = index / width;
			if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
				if (bestEdge == -1 || cost[index] < cost[bestEdge]) {
					bestEdge = index;
				}
				continue;
			}
			for (int[] offset : neighbours) {
				int nx = x + offset[0];
				int ny = y + offset[1];
				MapTile neighbour = map.getTile(nx, ny);
				if (neighbour == null) {
					continue;
				}
				int step = neighbour.isNavigable(null) ? 0 : 1;
				int neighbourIndex = ny * width + nx;
				if (cost[index] + step < cost[neighbourIndex]) {
					cost[neighbourIndex] = cost[index] + step;
					cameFrom[neighbourIndex] = index;
					if (step == 0) {
						queue.addFirst(neighbourIndex);
					} else {
						queue.addLast(neighbourIndex);
					}
				}
			}
		}
		if (bestEdge == -1) {
			return null;
		}

		// Walk back to the settlement and keep the obstacle nearest to it
		Integer firstObstacle = null;
		for (int index = bestEdge; index != -1; index = cameFrom[index]) {
			MapTile tile = map.getTile(index % width, index / width);
			if (tile != null && !tile.isNavigable(null)) {
				firstObstacle = index;
			}
		}
		return firstObstacle == null ? null : map.getTile(firstObstacle % width, firstObstacle / width).getWorldPositionOfCenter();
	}

	private static GridPoint2 findSettlementTile(GameContext gameContext, int settlementRegionId) {
		return gameContext.getEntities().values().stream()
				.filter(entity -> entity.isSettler())
				.map(settler -> gameContext.getAreaMap().getTile(settler.getLocationComponent().getWorldPosition()))
				.filter(tile -> tile != null && tile.getRegionId() == settlementRegionId)
				.map(MapTile::getTilePosition)
				.findFirst()
				.orElse(null);
	}

	private static Vector2 outsideEdge(MapTile edgeTile, TiledMap map) {
		Vector2 position = VectorUtils.toVector(edgeTile.getTilePosition());
		if (map.getTile(edgeTile.getTileX(), edgeTile.getTileY() + 1) == null) {
			return position.add(0, 0.48f);
		} else if (map.getTile(edgeTile.getTileX(), edgeTile.getTileY() - 1) == null) {
			return position.add(0, -0.48f);
		} else if (map.getTile(edgeTile.getTileX() - 1, edgeTile.getTileY()) == null) {
			return position.add(-0.48f, 0f);
		} else {
			return position.add(0.48f, 0f);
		}
	}
}
