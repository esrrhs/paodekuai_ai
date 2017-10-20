package ai;

public class CardInfo implements Cloneable
{
	public CardType type;
	public int max;
	public Robot r;
	public int[] cardstr;
	public int cardnum;

	public CardInfo()
	{

	}

	public CardInfo(CardType type, int max, Robot r, int[] cardstr, int cardnum)
	{
		this.type = type;
		this.max = max;
		this.r = r;
		this.cardstr = cardstr;
		this.cardnum = cardnum;
	}

	public String CardStr()
	{
		String str = "";
		if (cardstr.length == 0)
		{
			str += "pass";
		}
		else
		{
			for (int i : cardstr)
			{
				str += Logic.CardName[i - 1] + ",";
			}
		}
		return str;
	}

	public void copyfrom(CardInfo o)
	{
		type = o.type;
		max = o.max;
		r = o.r;
		cardstr = o.cardstr;
		cardnum = o.cardnum;
	}

	@Override
	public Object clone()
	{
		Object o = null;
		try
		{
			o = (CardInfo) super.clone();
		}
		catch (CloneNotSupportedException e)
		{
			System.out.println(e.toString());
		}
		return o;
	}

	@Override
	public int hashCode()
	{
		int hashv = 0;
		for (int c : cardstr)
		{
			hashv = ((hashv << 5) + hashv) + c; /* hash * 33 + c */
		}
		return hashv;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}

		if (o == null || getClass() != o.getClass())
		{
			return false;
		}

		CardInfo r = (CardInfo) o;

		if (this.cardstr.length != r.cardstr.length)
		{
			return false;
		}

		for (int i = 0; i < this.cardstr.length; i++)
		{
			if (this.cardstr[i] != r.cardstr[i])
			{
				return false;
			}
		}

		return true;
	}
}
