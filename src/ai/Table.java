package ai;

public class Table
{
	public Robot A;
	public Robot B;
	public Robot C;
	public Logic l;

	public void Run()
	{
		A = new Robot(this, 0);
		B = new Robot(this, 1);
		C = new Robot(this, 2);
		l = new Logic(this);
		A.next = B;
		B.next = C;
		C.next = A;

		long begin = System.currentTimeMillis();
		{
			A.Clear();
			B.Clear();
			C.Clear();
			l.Run();
		}
		System.out.println("time " + (System.currentTimeMillis() - begin));
	}

}
