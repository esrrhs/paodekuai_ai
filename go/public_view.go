package paodekuai

// MoveRecord 一条带座位归属的历史动作(过牌的 Cards 为空);等价 C++ MoveRecord。
type MoveRecord struct {
	PlayerId int
	Cards    CardSet
}

// PublicView 玩家(以及策略网络)被允许看到的公开观察,
// 绝不包含其他座位的私有手牌;等价 C++ PublicView / Java game.PublicView。
type PublicView struct {
	ViewingPlayerId int
	MyHand          CardSet
	CardCounts      [3]int       // 各家剩余张数
	History         []MoveRecord // 完整动作历史, 含过牌
	LastMove        [3]CardSet   // 各家最近一手(过牌为空)
	ToBeat          CardSet      // 空 = 自由出牌
	LastPlayerId    int
	ActivePlayerId  int
	BombCount       int
}

// PrevSeat 上家座位;等价 C++ PublicView::prevSeat。
func (v *PublicView) PrevSeat() int { return (v.ViewingPlayerId + 2) % 3 }

// NextSeat 下家座位;等价 C++ PublicView::nextSeat。
func (v *PublicView) NextSeat() int { return (v.ViewingPlayerId + 1) % 3 }

// CardCount 指定座位的剩余张数;等价 Java PublicView.getCardCount(int)。
func (v *PublicView) CardCount(playerId int) int { return v.CardCounts[playerId] }
