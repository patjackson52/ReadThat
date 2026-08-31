export interface ShowcaseRankedItem {
  id: string;
  rank_value: number;
}

export const STANDARD_FEED_PATTERN = [
  "image",
  "other",
  "video",
  "other",
  "image",
  "other",
  "video",
] as const;

export const MEDIA_FEED_PATTERN = [
  "image",
  "image",
  "video",
  "image",
  "image",
  "image",
  "video",
] as const;

/**
 * Merges independently keyset-ranked lanes into a stable editorial pattern.
 * Missing lanes fall back to the highest-ranked available head item so sparse
 * communities still return full pages without cross-community content.
 */
export function mergeShowcaseLanes<Lane extends string, Item extends ShowcaseRankedItem>(
  lanes: Record<Lane, readonly Item[]>,
  pattern: readonly Lane[],
  offset: number,
  limit: number,
): {
    items: Item[];
    consumedLast: Partial<Record<Lane, Item>>;
    hasMore: boolean;
  } {
  const laneNames = Object.keys(lanes) as Lane[];
  const indices = Object.fromEntries(laneNames.map((lane) => [lane, 0])) as Record<Lane, number>;
  const consumedLast: Partial<Record<Lane, Item>> = {};
  const items: Item[] = [];

  const availableHead = (lane: Lane): Item | undefined => lanes[lane][indices[lane]];
  const fallbackLane = (): Lane | null => {
    let selected: Lane | null = null;
    for (const lane of laneNames) {
      const candidate = availableHead(lane);
      if (!candidate) continue;
      const current = selected === null ? undefined : availableHead(selected);
      if (
        !current || candidate.rank_value > current.rank_value ||
        (candidate.rank_value === current.rank_value && candidate.id > current.id)
      ) {
        selected = lane;
      }
    }
    return selected;
  };

  for (let index = 0; index < limit; index += 1) {
    const preferred = pattern[(offset + index) % pattern.length];
    const lane = preferred && availableHead(preferred) ? preferred : fallbackLane();
    if (lane === null) break;
    const item = availableHead(lane);
    if (!item) break;
    indices[lane] += 1;
    consumedLast[lane] = item;
    items.push(item);
  }

  return {
    items,
    consumedLast,
    hasMore: laneNames.some((lane) => availableHead(lane) !== undefined),
  };
}
