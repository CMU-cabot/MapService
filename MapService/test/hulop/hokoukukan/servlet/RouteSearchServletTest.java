/*******************************************************************************
 * Copyright (c) 2026  Keio University and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *******************************************************************************/
package hulop.hokoukukan.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

public class RouteSearchServletTest {

	@Test
	public void parsesAndNormalizesHeading() throws Exception {
		assertNull(RouteSearchServlet.parseFromHeadingDeg(null));
		assertEquals(0.0, RouteSearchServlet.parseFromHeadingDeg("0"), 0.0);
		assertEquals(42.5, RouteSearchServlet.parseFromHeadingDeg(" 42.5 "), 0.0);
		assertEquals(0.0, RouteSearchServlet.parseFromHeadingDeg("360"), 0.0);
	}

	@Test
	public void rejectsInvalidHeading() throws Exception {
		for (String value : new String[] { "", " ", "north", "NaN", "Infinity", "-1", "360.0001" }) {
			try {
				RouteSearchServlet.parseFromHeadingDeg(value);
				fail("Expected invalid heading: " + value);
			} catch (Exception e) {
				assertEquals("from_heading_deg must be a finite number between 0 and 360", e.getMessage());
			}
		}
	}
}
