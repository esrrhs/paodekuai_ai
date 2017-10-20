package ai;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

public class Logic
{
	static Random ra = new Random();
	static double epsilon = 1e-6;

	public Table t;
	public Robot A;
	public Robot B;
	public Robot C;
	Random rand = new Random();
	boolean isin;

	public static int[] Card =
	{ 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };

	public static int[] AllCard =
	{ 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
			11, 12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 };

	public static String[] CardName =
	{ "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "小王", "大王" };

	public static int N = 100;

	public Logic(Table t)
	{
		this.t = t;
		this.A = t.A;
		this.B = t.B;
		this.C = t.C;
	}

	public void Run()
	{
		CardInfo lastbig = null;
		Robot cur = null;

		boolean test = false;
		if (!test)
		{
			lastbig = new CardInfo(CardType.ct_pass, 0, A, new int[0], 0);
			cur = A;
		}
		else
		{
			cur = C;

			boolean first = false;
			if (first)
			{
				lastbig = new CardInfo(CardType.ct_pass, 0, cur, new int[0], 0);
			}
			else
			{
				lastbig = new CardInfo(CardType.ct_single, GetCardFromName("8"), B, new int[]
				{ GetCardFromName("8"), }, 1);

			}
		}

		// 发牌
		Dispatch();

		// 开始打
		while (true)
		{
			lastbig = cur.Go(lastbig);
			if (IsEnd())
			{
				if (cur == A)
				{
					A.Win();
				}
				else if (cur == B)
				{
					B.Win();
				}
				else
				{
					C.Win();
				}
				break;
			}

			cur = cur.next;
		}
	}

	public boolean IsEnd()
	{
		return A.IsEnd() || B.IsEnd() || C.IsEnd();
	}

	public int GetCardFromName(String s)
	{
		for (int i = 0; i < CardName.length; i++)
		{
			if (s.equals(CardName[i]))
			{
				return i + 1;
			}
		}
		return 0;
	}

	public void Dispatch()
	{
		boolean test = true;
		if (test)
		{
			String a = "3,4,4,5,5,7,9,9,10,10,J,J,Q,Q,K,A";
			String b = "4,4,6,6,6,7,8,8,9,10,10,J,Q,Q,K,K";
			String c = "3,3,3,5,5,6,7,7,8,8,9,J,K,A,A,2";

			for (String s : a.split("\\,"))
			{
				A.AddCard(GetCardFromName(s));
			}

			for (String s : b.split("\\,"))
			{
				B.AddCard(GetCardFromName(s));
			}

			for (String s : c.split("\\,"))
			{
				C.AddCard(GetCardFromName(s));
			}
		}
		else
		{
			LinkedList<Integer> tmp = new LinkedList<Integer>();
			for (int i : AllCard)
			{
				tmp.add(i);
			}

			for (int i = 0; i < 16; i++)
			{
				int index = rand.nextInt(tmp.size());
				int card = tmp.get(index);
				tmp.remove(index);
				A.AddCard(card);
			}

			for (int i = 0; i < 16; i++)
			{
				int index = rand.nextInt(tmp.size());
				int card = tmp.get(index);
				tmp.remove(index);
				B.AddCard(card);
			}

			for (int i = 0; i < tmp.size(); i++)
			{
				int card = tmp.get(i);
				C.AddCard(card);
			}
		}

		System.out.println(">>>[" + A.no + "] :" + A.CardState());
		System.out.println(">>>[" + B.no + "] :" + B.CardState());
		System.out.println(">>>[" + C.no + "] :" + C.CardState());

		try
		{
			FileWriter fileWriter = new FileWriter("Result.txt");
			fileWriter.flush();
			fileWriter.close();
		}
		catch (Exception e)
		{

		}
	}

	public CardInfo OutCard(Robot r, CardInfo lastbig)
	{
		MCTSNode root = new MCTSNode();
		int N = 5000;
		CardInfo ret = MCTS(N, r, root, lastbig);

		boolean isshowson = true;
		if (isshowson)
		{
			System.out.println();
			for (Map.Entry<CardInfo, MCTSNode> e : root.son.entrySet())
			{
				MCTSNode s = e.getValue();
				System.out.println("    son:{" + s.cardInfo.CardStr() + "} {" + s.Value + "} {" + s.N + "})");
			}
		}

		boolean isdump = false;
		if (isdump)
		{
			String dump = DumpMCTS(N, 0, root, 0, 2);
			try
			{
				FileWriter fileWriter = new FileWriter("Result.txt", true);
				fileWriter.write("[" + r.no + "] :(" + r.CardState() + ")" + ret.CardStr() + "\n");
				fileWriter.write(dump);
				fileWriter.flush();
				fileWriter.close();
			}
			catch (Exception e)
			{

			}
		}
		return ret;
	}

	public String DumpMCTS(int N, int no, MCTSNode node, int deps, int need)
	{
		if (deps >= need)
		{
			return "";
		}
		String str = "";
		for (int i = 0; i < deps; i++)
		{
			str += "\t";
		}

		if (node.cardInfo == null)
		{
			str += "(" + no + ")" + "[{" + "null" + "}," + node.Value + "," + node.N + "]\n";
		}
		else
		{
			str += "(" + no + ")<" + node.cardInfo.r.no + ">[{";

			str += node.cardInfo.CardStr();

			str += "}," + node.Value + "," + node.N + "]\n";
		}

		int i = 0;
		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();
			str += DumpMCTS(node.N, i, s, deps + 1, need);
			i++;
		}
		return str;
	}

	public boolean MCTSCompare(CardInfo l, CardInfo r)
	{
		int lsum = 0;
		for (int c : l.cardstr)
		{
			lsum += c;
		}

		int rsum = 0;
		for (int c : r.cardstr)
		{
			rsum += c;
		}

		int lvalue = lsum + (20 - l.cardnum) * 15;
		int rvalue = rsum + (20 - r.cardnum) * 15;

		return lvalue < rvalue;
	}

	public CardInfo MCTS(int num, Robot r, MCTSNode node, CardInfo lastbig)
	{
		for (int i = 0; i < num; i++)
		{
			MCTSCal(r, node, lastbig);

			// 出现90%的胜率或者90%的选择，提前end
			if ((i + 1) % 1000 == 0)
			{
				float maxv = Integer.MIN_VALUE;
				int maxn = Integer.MIN_VALUE;
				for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
				{
					MCTSNode s = e.getValue();
					if (s.N > maxn)
					{
						maxn = s.N;
					}
					if (s.Value > maxv)
					{
						maxv = s.Value;
					}
				}
				if (maxn > i * 90 / 100 || maxv > i * 90 / 100)
				{
					break;
				}
			}
		}

		int totalvalue = 0;
		HashMap<CardInfo, MCTSNode> win = new HashMap<CardInfo, MCTSNode>();
		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();
			totalvalue += s.Value;
			if (s.Value >= s.N)
			{
				win.put(e.getKey(), e.getValue());
			}
		}

		if (!win.isEmpty())
		{
			// 必赢
			node.son = win;
			return MCTSSelectByWin(r, node, lastbig);
		}
		else if (totalvalue == 0)
		{
			// 必输
			return MCTSSelectByLose(r, node, lastbig);
		}
		else
		{
			// 同value的权重计算，选个价值最低的打出去
			return MCTSSelect(r, node, lastbig);
		}
	}

	public CardInfo MCTSSelect(Robot r, MCTSNode node, CardInfo lastbig)
	{
		CardInfo cardinfo = null;
		float max = -9999999;
		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();
			// value正负1/100认为是一样的
			if (s.Value >= max - max / 100 && s.Value <= max + max / 100)
			{
				max = Math.max(s.Value, max);

				// 选价值最低的打出去
				if (MCTSCompare(s.cardInfo, cardinfo))
				{
					cardinfo = s.cardInfo;
				}
			}
			else if (s.Value > max)
			{
				max = s.Value;
				cardinfo = s.cardInfo;
			}
		}

		return cardinfo;
	}

	public CardInfo MCTSSelectByLose(Robot r, MCTSNode node, CardInfo lastbig)
	{
		boolean usemin = true;
		if (r.no == 2)
		{
			usemin = false;
		}

		CardInfo cardinfo = null;
		int min = 9999999;
		int max = -9999999;
		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();

			int sum = 0;
			for (int c : s.cardInfo.cardstr)
			{
				sum += c;
			}

			if (usemin)
			{
				if (sum < min || (cardinfo != null && cardinfo.type == CardType.ct_pass))
				{
					min = sum;
					cardinfo = s.cardInfo;
				}
			}
			else
			{
				if (sum > max || (cardinfo != null && cardinfo.type == CardType.ct_pass))
				{
					max = sum;
					cardinfo = s.cardInfo;
				}
			}
		}

		return cardinfo;
	}

	public CardInfo MCTSSelectByWin(Robot r, MCTSNode node, CardInfo lastbig)
	{
		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();
			if (s.cardInfo.cardnum >= r.cardnum && s.cardInfo.type != CardType.ct_four_plus_two
					&& s.cardInfo.type != CardType.ct_four_plus_three)
			{
				return s.cardInfo;
			}
		}

		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();
			if (s.cardInfo.type != CardType.ct_four_plus_two && s.cardInfo.type != CardType.ct_four_plus_three)
			{
				if (IsLastTwoOut(r, s.cardInfo))
				{
					return s.cardInfo;
				}
			}
		}

		int minmax = 999999;
		CardInfo cardinfo = null;
		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();
			if (s.cardInfo.max < minmax && r.cardmap[s.cardInfo.max] != 4
					&& !(have_double_king(r) && s.cardInfo.max == 14 || s.cardInfo.max == 15))
			{
				cardinfo = s.cardInfo;
				minmax = s.cardInfo.max;
			}
		}

		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();

			if (cardinfo == null)
			{
				cardinfo = s.cardInfo;
			}
			else
			{
				if (cardinfo.type == CardType.ct_pass)
				{
					cardinfo = s.cardInfo;
				}
				else if (cardinfo.type == CardType.ct_double_king && cardinfo.max != 14 && cardinfo.max != 15)
				{
					cardinfo = s.cardInfo;
				}
				else if (s.cardInfo.max == cardinfo.max && s.cardInfo.cardnum > cardinfo.cardnum
						&& s.cardInfo.type != CardType.ct_four_plus_two
						&& s.cardInfo.type != CardType.ct_four_plus_three)
				{
					cardinfo = s.cardInfo;
				}
				else if (s.cardInfo.type == cardinfo.type && r.cardmap[s.cardInfo.max] != 4)
				{
					if (MCTSCompare(s.cardInfo, cardinfo))
					{
						cardinfo = s.cardInfo;
					}
				}
			}
		}

		return cardinfo;
	}

	public class MCTSCalReslut
	{
		public int[] cardnum = new int[3];

		public MCTSCalReslut(int cardnum0, int cardnum1, int cardnum2)
		{
			cardnum[0] = cardnum0;
			cardnum[1] = cardnum1;
			cardnum[2] = cardnum2;
		}
	}

	public MCTSCalReslut MCTSCal(Robot r, MCTSNode node, CardInfo lastbig)
	{
		if (IsEnd())
		{
			return new MCTSCalReslut(A.cardnum, B.cardnum, C.cardnum);
		}

		MCTSNode select = null;
		if (!node.sonall)
		{
			ArrayList<CardInfo> outlist;
			if (lastbig.r == r)
			{
				outlist = FindFirstOutCard(r);
			}
			else
			{
				outlist = FindBigger(r, lastbig);
			}

			for (CardInfo c : outlist)
			{
				MCTSNode s = node.son.get(c);
				if (s == null)
				{
					s = new MCTSNode();
					s.cardInfo = c;
					node.son.put(c, s);
					select = s;
					break;
				}
			}

			if (select == null)
			{
				select = MCTSChoose(r, node);
				node.sonall = true;
			}
		}
		else
		{
			select = MCTSChoose(r, node);
		}

		r.RemoveCard(select.cardInfo);

		CardInfo newlastbig;
		if (select.cardInfo.type == CardType.ct_pass)
		{
			newlastbig = (CardInfo) lastbig.clone();
		}
		else
		{
			newlastbig = (CardInfo) select.cardInfo.clone();
		}

		Robot next = r.next;
		MCTSCalReslut ret = MCTSCal(next, select, newlastbig);

		r.AddCard(select.cardInfo);

		select.Value += (16 - ret.cardnum[r.no]) / 16;

		node.N++;

		return ret;
	}

	public MCTSNode MCTSChoose(Robot r, MCTSNode node)
	{
		int c = 1;
		float max = -999999;
		MCTSNode ret = null;
		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();
			float value = 0;
			if (s.N == 0)
			{
				return s;
			}
			value += (float) s.Value / (s.N + epsilon) + c * (float) (Math.sqrt(Math.log(node.N + 1) / (s.N + epsilon)))
					+ ra.nextDouble() * epsilon;
			if (value > max)
			{
				max = value;
				ret = s;
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBigger(Robot r, CardInfo lastbig)
	{
		ArrayList<CardInfo> ret = new ArrayList<CardInfo>();

		if (lastbig.type == CardType.ct_double_king)
		{
			ret.add(new CardInfo(CardType.ct_pass, 0, r, new int[0], 0));
			return ret;
		}

		if (lastbig.type == CardType.ct_single)
		{
			ret = FindBiggerSingle(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_double)
		{
			ret = FindBiggerDouble(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_three)
		{
			ret = FindBiggerThree(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_boom)
		{
			ret = FindBiggerBoom(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_three_plus_one)
		{
			ret = FindBiggerThreePlusOne(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_three_plus_two)
		{
			ret = FindBiggerThreePlusTwo(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_four_plus_two)
		{
			ret = FindBiggerFourPlusTwo(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_four_plus_three)
		{
			ret = FindBiggerFourPlusThree(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_continue)
		{
			ret = FindBiggerContinue(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_double_continue)
		{
			ret = FindBiggerDoubleContinue(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_double_three)
		{
			ret = FindBiggerDoubleThree(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_double_three_plus_one)
		{
			ret = FindBiggerDoubleThreePlusOne(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_double_three_plus_two)
		{
			ret = FindBiggerDoubleThreePlusTwo(ret, r, lastbig);
		}

		if (lastbig.type != CardType.ct_boom)
		{
			CardInfo tmpboom = (CardInfo) lastbig.clone();
			tmpboom.max = 0;
			tmpboom.cardnum = 4;

			ret = FindBiggerBoom(ret, r, tmpboom);
		}

		if (ret.isEmpty())
		{
			ret.add(new CardInfo(CardType.ct_pass, 0, r, new int[0], 0));
		}

		return ret;
	}

	public int[] AppendCard(int[] src, int start, int[] add)
	{
		for (int k = 0; k < add.length; k++)
		{
			src[start] = add[k];
			start++;
		}
		return src;
	}

	public ArrayList<CardInfo> FindBiggerDoubleThreePlusTwo(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		int num = lastbig.cardnum / 5;
		for (int i = lastbig.max + 1; i <= 12 - num + 1; i++)
		{
			boolean find = true;
			for (int j = 0; j < num; j++)
			{
				if (r.cardmap[i + j] > 2)
				{
				}
				else
				{
					find = false;
					break;
				}
			}

			if (find)
			{
				for (int j = 0; j < num; j++)
				{
					r.cardmap[i + j] -= 3;
				}

				ret = ChooseDoubleThreePlusTwo(ret, r, lastbig, i, num, new int[0], 1);

				for (int j = 0; j < num; j++)
				{
					r.cardmap[i + j] += 3;
				}
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> ChooseDoubleThreePlusTwo(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig, int starti,
			int deps, int[] card, int loopstart)
	{
		if (deps == 0)
		{
			int[] cardstr = new int[lastbig.cardnum];
			for (int k = 0; k < lastbig.cardnum / 5 * 3; k += 3)
			{
				cardstr[k] = starti + (k / 3);
				cardstr[k + 1] = starti + (k / 3);
				cardstr[k + 2] = starti + (k / 3);
			}
			cardstr = AppendCard(cardstr, lastbig.cardnum / 5 * 3, card);

			ret.add(new CardInfo(CardType.ct_double_three_plus_two, starti, r, cardstr, lastbig.cardnum));

			return ret;
		}

		int mindobule = 999;
		for (int i = loopstart; i <= 12; i++)
		{
			if (r.cardmap[i] > 1)
			{
				if (IsSimpleDouble(r, i))
				{
					if (i < mindobule)
					{
						mindobule = i;
					}
					else
					{
						continue;
					}
				}

				int[] mycard = new int[card.length + 2];
				mycard = AppendCard(mycard, 0, card);
				mycard[card.length] = i;
				mycard[card.length + 1] = i;

				r.cardmap[i] -= 2;

				ret = ChooseDoubleThreePlusTwo(ret, r, lastbig, starti, deps - 1, mycard, i);

				r.cardmap[i] += 2;
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleThreePlusOne(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		int num = lastbig.cardnum / 4;
		for (int i = lastbig.max + 1; i <= 12 - num + 1; i++)
		{
			boolean find = true;
			for (int j = 0; j < num; j++)
			{
				if (r.cardmap[i + j] > 2)
				{
				}
				else
				{
					find = false;
					break;
				}
			}

			if (find)
			{
				for (int j = 0; j < num; j++)
				{
					r.cardmap[i + j] -= 3;
				}

				ret = ChooseDoubleThreePlusOne(ret, r, lastbig, i, num, new int[0], 1);

				for (int j = 0; j < num; j++)
				{
					r.cardmap[i + j] += 3;
				}
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> ChooseDoubleThreePlusOne(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig, int starti,
			int deps, int[] card, int loopstart)
	{
		if (deps == 0)
		{
			int[] cardstr = new int[lastbig.cardnum];
			for (int k = 0; k < lastbig.cardnum / 4 * 3; k += 3)
			{
				cardstr[k] = starti + (k / 3);
				cardstr[k + 1] = starti + (k / 3);
				cardstr[k + 2] = starti + (k / 3);
			}
			cardstr = AppendCard(cardstr, lastbig.cardnum / 4 * 3, card);

			ret.add(new CardInfo(CardType.ct_double_three_plus_one, starti, r, cardstr, lastbig.cardnum));

			return ret;
		}

		int minsingle = 999;
		for (int i = loopstart; i <= 15; i++)
		{
			if (r.cardmap[i] > 0)
			{
				if (IsSimpleSingle(r, i))
				{
					if (i < minsingle)
					{
						minsingle = i;
					}
					else
					{
						continue;
					}
				}

				int[] mycard = new int[card.length + 1];
				mycard = AppendCard(mycard, 0, card);
				mycard[card.length] = i;

				r.cardmap[i]--;

				ret = ChooseDoubleThreePlusOne(ret, r, lastbig, starti, deps - 1, mycard, i);

				r.cardmap[i]++;
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleThree(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		int num = lastbig.cardnum / 3;
		for (int i = lastbig.max + 1; i <= 12 - num + 1; i++)
		{
			boolean find = true;
			for (int j = 0; j < num; j++)
			{
				if (r.cardmap[i + j] > 2)
				{
				}
				else
				{
					find = false;
					break;
				}
			}

			if (find)
			{
				int[] cardstr = new int[lastbig.cardnum];
				for (int j = 0; j < lastbig.cardnum; j += 3)
				{
					cardstr[j] = i + (j / 3);
					cardstr[j + 1] = i + (j / 3);
					cardstr[j + 2] = i + (j / 3);
				}

				ret.add(new CardInfo(CardType.ct_double_three, i, r, cardstr, lastbig.cardnum));
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleContinue(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		int num = lastbig.cardnum / 2;
		for (int i = lastbig.max + 1; i <= 12 - num + 1; i++)
		{
			boolean find = true;
			for (int j = 0; j < num; j++)
			{
				if (r.cardmap[i + j] > 1)
				{
				}
				else
				{
					find = false;
					break;
				}
			}

			if (find)
			{
				int[] cardstr = new int[lastbig.cardnum];
				for (int j = 0; j < lastbig.cardnum; j += 2)
				{
					cardstr[j] = i + (j / 2);
					cardstr[j + 1] = i + (j / 2);
				}

				ret.add(new CardInfo(CardType.ct_double_continue, i, r, cardstr, lastbig.cardnum));
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerContinue(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		for (int i = lastbig.max + 1; i <= 12 - lastbig.cardnum + 1; i++)
		{
			boolean find = true;
			for (int j = 0; j < lastbig.cardnum; j++)
			{
				if (r.cardmap[i + j] > 0)
				{
				}
				else
				{
					find = false;
					break;
				}
			}

			if (find)
			{
				int[] cardstr = new int[lastbig.cardnum];
				for (int j = 0; j < lastbig.cardnum; j++)
				{
					cardstr[j] = i + j;
				}

				ret.add(new CardInfo(CardType.ct_continue, i, r, cardstr, lastbig.cardnum));
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerFourPlusThree(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 3)
			{
				for (int j = 1; j <= 13; j++)
				{
					if (i != j && r.cardmap[j] > 1)
					{
						r.cardmap[j]--;
						for (int k = j; k <= 15; k++)
						{
							if (i != k && r.cardmap[k] > 0)
							{
								r.cardmap[k]--;
								for (int l = k; l <= 15; l++)
								{
									if (i != l && r.cardmap[l] > 0)
									{
										int[] cardstr = new int[lastbig.cardnum];
										cardstr[0] = i;
										cardstr[1] = i;
										cardstr[2] = i;
										cardstr[3] = i;
										cardstr[4] = j;
										cardstr[5] = k;
										cardstr[6] = l;

										ret.add(new CardInfo(CardType.ct_four_plus_three, i, r, cardstr,
												lastbig.cardnum));
									}
								}
								r.cardmap[k]++;
							}
						}
						r.cardmap[j]++;
					}
				}
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerFourPlusTwo(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 3)
			{
				// 随机选2个
				int mindouble = 999;
				for (int j = 1; j <= 13; j++)
				{
					if (i != j && r.cardmap[j] > 1)
					{
						if (IsSimpleDouble(r, j))
						{
							if (j < mindouble)
							{
								mindouble = j;
							}
							else
							{
								continue;
							}
						}

						int[] cardstr = new int[lastbig.cardnum];
						cardstr[0] = i;
						cardstr[1] = i;
						cardstr[2] = i;
						cardstr[3] = i;
						cardstr[4] = j;
						cardstr[5] = j;

						ret.add(new CardInfo(CardType.ct_four_plus_two, i, r, cardstr, lastbig.cardnum));
					}
				}

				int minsingle = 999;
				for (int j = 1; j <= 15; j++)
				{
					if (i != j && r.cardmap[j] > 0)
					{
						if (IsSimpleSingle(r, j))
						{
							if (j < minsingle)
							{
								minsingle = j;
							}
							else
							{
								continue;
							}
						}

						int minsingleex = 999;
						for (int k = j + 1; k <= 15; k++)
						{
							if (i != k && r.cardmap[k] > 0)
							{
								if (IsSimpleSingle(r, k))
								{
									if (k < minsingleex)
									{
										minsingleex = k;
									}
									else
									{
										continue;
									}
								}

								int[] cardstr = new int[lastbig.cardnum];
								cardstr[0] = i;
								cardstr[1] = i;
								cardstr[2] = i;
								cardstr[3] = i;
								cardstr[4] = j;
								cardstr[5] = k;

								ret.add(new CardInfo(CardType.ct_four_plus_two, i, r, cardstr, lastbig.cardnum));
							}
						}
					}
				}
			}
		}
		return ret;
	}

	public boolean IsSimpleSingle(Robot r, int i)
	{
		if (i >= 5)
		{
			if (r.cardmap[i - 4] > 0 && r.cardmap[i - 3] > 0 && r.cardmap[i - 2] > 0 && r.cardmap[i - 1] > 0)
			{
				return false;
			}
		}

		if (i <= 8)
		{
			if (r.cardmap[i + 4] > 0 && r.cardmap[i + 3] > 0 && r.cardmap[i + 2] > 0 && r.cardmap[i + 1] > 0)
			{
				return false;
			}
		}

		return true;
	}

	public boolean IsSimpleDouble(Robot r, int i)
	{
		if (r.cardmap[i] != 2)
		{
			return false;
		}

		if (i >= 3)
		{
			if (r.cardmap[i - 2] > 1 && r.cardmap[i - 1] > 1)
			{
				return false;
			}
		}

		if (i <= 11)
		{
			if (r.cardmap[i + 2] > 1 && r.cardmap[i + 1] > 1)
			{
				return false;
			}
		}

		return true;
	}

	public ArrayList<CardInfo> FindBiggerThreePlusTwo(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 2)
			{
				// 随机选个
				int mindouble = 999;
				for (int j = 1; j <= 13; j++)
				{
					if (i != j && r.cardmap[j] > 1)
					{
						if (IsSimpleDouble(r, j))
						{
							if (j < mindouble)
							{
								mindouble = j;
							}
							else
							{
								continue;
							}
						}

						int[] cardstr = new int[lastbig.cardnum];
						cardstr[0] = i;
						cardstr[1] = i;
						cardstr[2] = i;
						cardstr[3] = j;
						cardstr[4] = j;

						ret.add(new CardInfo(CardType.ct_three_plus_two, i, r, cardstr, lastbig.cardnum));
					}
				}

				int minsingle = 999;
				for (int j = 1; j <= 15; j++)
				{
					if (i != j && r.cardmap[j] > 0)
					{
						if (IsSimpleSingle(r, j))
						{
							if (j < minsingle)
							{
								minsingle = j;
							}
							else
							{
								continue;
							}
						}

						int minsingleex = 999;
						for (int k = j + 1; k <= 15; k++)
						{
							if (i != k && r.cardmap[k] > 0)
							{
								if (IsSimpleSingle(r, k))
								{
									if (k < minsingleex)
									{
										minsingleex = k;
									}
									else
									{
										continue;
									}
								}

								int[] cardstr = new int[lastbig.cardnum];
								cardstr[0] = i;
								cardstr[1] = i;
								cardstr[2] = i;
								cardstr[3] = j;
								cardstr[4] = k;

								ret.add(new CardInfo(CardType.ct_three_plus_two, i, r, cardstr, lastbig.cardnum));
							}
						}
					}
				}
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerThreePlusOne(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 2)
			{
				// 随机选个
				int minsingle = 999;
				for (int j = 1; j <= 15; j++)
				{
					if (i != j && r.cardmap[j] > 0)
					{
						if (IsSimpleSingle(r, j))
						{
							if (j < minsingle)
							{
								minsingle = j;
							}
							else
							{
								continue;
							}
						}

						int[] cardstr = new int[lastbig.cardnum];
						cardstr[0] = i;
						cardstr[1] = i;
						cardstr[2] = i;
						cardstr[3] = j;

						ret.add(new CardInfo(CardType.ct_three_plus_one, i, r, cardstr, lastbig.cardnum));
					}
				}
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerThree(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 2)
			{
				int[] cardstr = new int[lastbig.cardnum];
				cardstr[0] = i;
				cardstr[1] = i;
				cardstr[2] = i;
				ret.add(new CardInfo(CardType.ct_three, i, r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDouble(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < lastbig.cardnum)
		{
			return ret;
		}

		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 1)
			{
				int[] cardstr = new int[lastbig.cardnum];
				cardstr[0] = i;
				cardstr[1] = i;
				ret.add(new CardInfo(CardType.ct_double, i, r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerSingle(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = lastbig.max + 1; i <= 15; i++)
		{
			if (r.cardmap[i] > 0)
			{
				int[] cardstr = new int[lastbig.cardnum];
				cardstr[0] = i;
				ret.add(new CardInfo(CardType.ct_single, i, r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerBoom(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		if (r.cardnum < 4)
		{
			return ret;
		}

		for (int i = lastbig.max + 1; i <= 12; i++)
		{
			if (r.cardmap[i] > 3)
			{
				int[] cardstr = new int[lastbig.cardnum];
				cardstr[0] = i;
				cardstr[1] = i;
				cardstr[2] = i;
				cardstr[3] = i;
				ret.add(new CardInfo(CardType.ct_boom, i, r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public boolean have_double_king(Robot r)
	{
		return r.cardmap[14] > 0 && r.cardmap[15] > 0;
	}

	public ArrayList<CardInfo> FindFirstOutCard(Robot r)
	{
		ArrayList<CardInfo> ret = new ArrayList<CardInfo>();

		CardInfo lastbig = new CardInfo(CardType.ct_single, 0, r, new int[0], 0);

		lastbig.type = CardType.ct_single;
		{
			lastbig.cardnum = 1;
			ret = FindBiggerSingle(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_double;
		{
			lastbig.cardnum = 2;
			ret = FindBiggerDouble(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_three;
		{
			lastbig.cardnum = 3;
			ret = FindBiggerThree(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_three_plus_one;
		{
			lastbig.cardnum = 4;
			ret = FindBiggerThreePlusOne(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_three_plus_two;
		{
			lastbig.cardnum = 5;
			ret = FindBiggerThreePlusTwo(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_four_plus_two;
		if (is_need_four_plus_two(r))
		{
			lastbig.cardnum = 6;
			ret = FindBiggerFourPlusTwo(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_four_plus_three;
		if (is_need_four_plus_two_double(r))
		{
			lastbig.cardnum = 8;
			ret = FindBiggerFourPlusThree(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_continue;
		for (int i = 5; i <= 13; i++)
		{
			lastbig.cardnum = i;
			ret = FindBiggerContinue(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_double_continue;
		for (int i = 4; i <= 12; i += 2)
		{
			lastbig.cardnum = i;
			ret = FindBiggerDoubleContinue(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_double_three;
		for (int i = 6; i <= 12; i += 3)
		{
			lastbig.cardnum = i;
			ret = FindBiggerDoubleThree(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_double_three_plus_one;
		for (int i = 8; i <= 16; i += 4)
		{
			lastbig.cardnum = i;
			ret = FindBiggerDoubleThreePlusOne(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_double_three_plus_two;
		for (int i = 10; i <= 15; i += 5)
		{
			lastbig.cardnum = i;
			ret = FindBiggerDoubleThreePlusTwo(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_boom;
		{
			lastbig.cardnum = 4;
			ret = FindBiggerBoom(ret, r, lastbig);
		}

		if (have_double_king(r))
		{
			ret.add(new CardInfo(CardType.ct_double_king, 15, r, new int[]
			{ 14, 15 }, 2));
		}

		return ret;
	}

	public boolean is_need_four_plus_two(Robot r)
	{
		if (r.cardnum <= 8)
		{
			return true;
		}
		return false;
	}

	public boolean is_need_four_plus_two_double(Robot r)
	{
		if (r.cardnum <= 10)
		{
			return true;
		}
		return false;
	}

	public boolean is_need_boom(Robot r)
	{
		if (r.cardnum <= 5 || r.next.cardnum <= 5 || r.next.cardnum <= 5)
		{
			return true;
		}
		return false;
	}

	public boolean IsLastTwoOut(Robot r, CardInfo c)
	{
		r.RemoveCard(c);

		ArrayList<CardInfo> ret = FindBigger(r.next, c);
		if (ret.size() != 1)
		{
			r.AddCard(c);
			return false;
		}
		ret = FindBigger(r.next.next, c);
		if (ret.size() != 1)
		{
			r.AddCard(c);
			return false;
		}

		ret = FindFirstOutCard(r);
		if (ret.size() != 1)
		{
			r.AddCard(c);
			return false;
		}

		r.AddCard(c);

		return true;
	}
}
