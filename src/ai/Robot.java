package ai;

public class Robot
{
	public int no;
	public Table t;
	public Robot next;
	public int[] cardmap = new int[16];
	public int cardnum;

	public Robot(Table t, int no)
	{
		this.t = t;
		this.no = no;
	}

	public void Clear()
	{
		for (int i = 0; i < cardmap.length; i++)
		{
			cardmap[i] = 0;
		}
		cardnum = 0;
	}

	public void AddCard(int i)
	{
		cardmap[i]++;
		cardnum++;
	}

	public String CardState()
	{
		String ret = "";
		for (int i = 0; i < cardmap.length; i++)
		{
			for (int j = 0; j < cardmap[i]; j++)
			{
				ret += Logic.CardName[i - 1] + ",";
			}
		}
		return ret;
	}

	public CardInfo Go(CardInfo lastbig)
	{
		CardInfo ret = null;
		CardInfo out = null;

		System.out.print("[" + no + "] :(" + CardState() + ")");

		out = OutCard(lastbig);

		if (out.type == CardType.ct_pass)
		{
			ret = lastbig;
		}
		else
		{
			ret = out;
		}

		System.out.println(out.CardStr());

		RemoveCard(out);

		return ret;
	}

	public void AddCard(CardInfo out)
	{
		if (out.cardstr.length > 0)
		{
			for (int c : out.cardstr)
			{
				cardmap[c]++;
			}
		}
		cardnum += out.cardstr.length;
	}

	public void RemoveCard(CardInfo out)
	{
		if (out.cardstr.length > 0)
		{
			for (int c : out.cardstr)
			{
				cardmap[c]--;
			}
		}
		cardnum -= out.cardstr.length;
	}

	public CardInfo OutCard(CardInfo lastbig)
	{
		return t.l.OutCard(this, lastbig);
	}

	public void Win()
	{
		System.out.println("[" + no + "] :Win");

	}

	public boolean IsEnd()
	{
		return cardnum == 0;
	}
}
