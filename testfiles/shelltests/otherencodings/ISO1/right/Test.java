public class Test {
        
	public static char specialCharacter = '�';

        public double scalarProduct(Point u, Point v) {
                return u.x * v.x + u.y * v.y + u.z * v.z;
        }

        public Point normalize(Point u) {
                return u.multiply(1/u.norm());
        }
}
