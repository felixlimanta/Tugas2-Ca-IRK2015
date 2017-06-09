package org.felixlimanta.RSAEncryptor.model;

import java.util.Arrays;

/**
 * Created by ASUS on 07/06/17.
 */
public class BigInt implements Comparable<BigInt> {

  private static final int DIGITS_PER_INT = 9;
  private static final int BASE = 1000000000;
  private static final int MAX_MAG_LENGTH = Integer.MAX_VALUE / Integer.SIZE + 1;

  /**
   * This mask is used to obtain the value of an int as if it were unsigned.
   * Usage: <code>long a = int b & LONG_MASK</code>
   */
  final static long LONG_MASK = 0xffffffffL;

  private static final int KARATSUBA_THRESHOLD = 50;

  private static long bitsPerDigit[] = { 0, 0,
      1024, 1624, 2048, 2378, 2648, 2875, 3072, 3247, 3402, 3543, 3672,
      3790, 3899, 4001, 4096, 4186, 4271, 4350, 4426, 4498, 4567, 4633,
      4696, 4756, 4814, 4870, 4923, 4975, 5025, 5074, 5120, 5166, 5210,
      5253, 5295};

  private static int digitsPerInt[] = {0, 0, 30, 19, 15, 13, 11,
      11, 10, 9, 9, 8, 8, 8, 8, 7, 7, 7, 7, 7, 7, 7, 6, 6, 6, 6,
      6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 5};

  private static int intRadix[] = {0, 0,
      0x40000000, 0x4546b3db, 0x40000000, 0x48c27395, 0x159fd800,
      0x75db9c97, 0x40000000, 0x17179149, 0x3b9aca00, 0xcc6db61,
      0x19a10000, 0x309f1021, 0x57f6c100, 0xa2f1b6f,  0x10000000,
      0x18754571, 0x247dbc80, 0x3547667b, 0x4c4b4000, 0x6b5a6e1d,
      0x6c20a40,  0x8d2d931,  0xb640000,  0xe8d4a51,  0x1269ae40,
      0x17179149, 0x1cb91000, 0x23744899, 0x2b73a840, 0x34e63b41,
      0x40000000, 0x4cfa3cc1, 0x5c13d840, 0x6d91b519, 0x39aa400
  };

  private byte sign;
  private int[] mag;

  public static BigInt ZERO = new BigInt(new int[0], (byte) 0);
  public static BigInt ONE = new BigInt(valueOf(1));
  public static BigInt NEGATIVE_ONE = new BigInt(valueOf(-1));




  //region Constructor
  //------------------------------------------------------------------------------------------------

  public BigInt(int[] val) {
    if (val.length == 0)
      throw new NumberFormatException("Zero length BigInteger");

    if (val[0] < 0) {
      mag = makePositive(val);
      sign = -1;
    } else {
      mag = stripLeadingZeros(val);
      if (mag.length == 0)
        sign = 0;
      else
        sign = 1;
    }

    if (mag.length >= MAX_MAG_LENGTH)
      checkRange();
  }

  BigInt(int[] mag, byte sign) {
    this.sign = (mag.length == 0 ? 0 : sign);
    this.mag = mag;
    if (mag.length >= MAX_MAG_LENGTH)
      checkRange();
  }

  public BigInt(String val) {
    this(valueOf(val, 10));
  }

  public BigInt(long val) {
    this(valueOf(val));
  }

  public BigInt(BigInt b) {
    mag = Arrays.copyOf(b.mag, b.mag.length);
    sign = b.sign;
  }

  //------------------------------------------------------------------------------------------------
  //endregion

  //region Conversion
  //------------------------------------------------------------------------------------------------

  public static BigInt valueOf(String val, int radix) {
    /*
    int strLen = val.length();
    if (strLen == 0)
      throw new NumberFormatException("Zero length BigInteger");

    byte sign;
    int shift;
    if (val.charAt(0) == '-') {
      strLen--;
      sign = -1;
      shift = 1;
    } else {
      sign = 1;
      shift = 0;
    }

    int intLen = (strLen - 1) / DIGITS_PER_INT + 1;
    int firstLen = strLen - (intLen - 1) * DIGITS_PER_INT;
    int[] digits = new int[intLen];
    for (int i = 0; i < intLen; ++i) {
      String block = val.substring(
          Math.max(firstLen + (i - 1) * DIGITS_PER_INT + shift, shift),
          firstLen + (i * DIGITS_PER_INT) + shift
      );
      digits[i] = Integer.parseInt(block);
    }
    return new BigInt(digits, sign);
    */

    int cursor = 0, numDigits;
    final int len = val.length();

    if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX)
      throw new NumberFormatException("Radix out of range");
    if (len == 0)
      throw new NumberFormatException("Zero length BigInteger");

    // Check for at most one leading sign
    byte sign = 1;
    int index1 = val.lastIndexOf('-');
    int index2 = val.lastIndexOf('+');
    if (index1 >= 0) {
      if (index1 != 0 || index2 >= 0) {
        throw new NumberFormatException("Illegal embedded sign character");
      }
      sign = -1;
      cursor = 1;
    } else if (index2 >= 0) {
      if (index2 != 0) {
        throw new NumberFormatException("Illegal embedded sign character");
      }
      cursor = 1;
    }
    if (cursor == len)
      throw new NumberFormatException("Zero length BigInteger");

    // Skip leading zeros and compute number of digits in magnitude
    while (cursor < len &&
        Character.digit(val.charAt(cursor), radix) == 0) {
      cursor++;
    }

    int[] mag;
    if (cursor == len) {
      return ZERO;
    }
    numDigits = len - cursor;

    // Pre-allocate array of expected size. May be too large but can
    // never be too small. Typically exact.
    long numBits = ((numDigits * bitsPerDigit[radix]) >>> 10) + 1;
    if (numBits + 31 >= (1L << 32)) {
      reportOverflow();
    }
    int numWords = (int) (numBits + 31) >>> 5;
    int[] magnitude = new int[numWords];

    // Process first (potentially short) digit group
    int firstGroupLen = numDigits % digitsPerInt[radix];
    if (firstGroupLen == 0)
      firstGroupLen = digitsPerInt[radix];
    String group = val.substring(cursor, cursor += firstGroupLen);
    magnitude[numWords - 1] = Integer.parseInt(group, radix);
    if (magnitude[numWords - 1] < 0)
      throw new NumberFormatException("Illegal digit");

    // Process remaining digit groups
    int superRadix = intRadix[radix];
    int groupVal = 0;
    while (cursor < len) {
      group = val.substring(cursor, cursor += digitsPerInt[radix]);
      groupVal = Integer.parseInt(group, radix);
      if (groupVal < 0)
        throw new NumberFormatException("Illegal digit");
      destructiveMulAdd(magnitude, superRadix, groupVal);
    }
    // Required for cases where the array was overallocated.
    mag = stripLeadingZeros(magnitude);
    if (mag.length >= MAX_MAG_LENGTH) {
      checkRange(mag);
    }
    return new BigInt(mag, sign);
  }

  public static BigInt valueOf(long val) {
    if (val == 0)
      return ZERO;

    byte sign;
    if (val < 0) {
      val = -val;
      sign = -1;
    } else {
      sign = 1;
    }

    int[] mag;
    int highWord = (int)(val >>> 32);
    if (highWord == 0) {
      mag = new int[1];
      mag[0] = (int) val;
    } else {
      mag = new int[2];
      mag[0] = highWord;
      mag[1] = (int) val;
    }
    return new BigInt(mag, sign);
  }

  /**
   * Returns decimal representation of this BigInt object. The minus '-' sign is appended
   * when appropriate.
   *
   * @return Decimal string representation of this BigInt
   */
  @Override
  public String toString() {
    if (sign == 0)
      return "0";

    StringBuilder s = new StringBuilder();
    if (sign < 0)
      s.append('-');
    s.append(Integer.toUnsignedString(mag[0]));
    for (int i = 1; i < mag.length; ++i)
      s.append(
          String.format("%09d", Integer.toUnsignedLong(mag[i]))
      );
    return s.toString();
  }

  public String toBinaryString() {
    if (sign == 0)
      return "0";

    StringBuilder s = new StringBuilder();
    if (sign < 0)
      s.append('-');
    s.append(Integer.toBinaryString(mag[0]));
    for (int i = 1; i < mag.length; ++i) {
      // Pad with zeros
      String group = Integer.toBinaryString(mag[i]);
      for (int j = 0; j < 32 - group.length(); ++j) {
        s.append('0');
      }
      s.append(group);
    }
    return s.toString();
  }

  /**
   * toString function for debugging. Generates string with sign and array contents
   *
   * @param debug True to print debug data, false to print decimal string
   * @return String representation of this BigInt
   */
  String toString(boolean debug) {
    if (debug)
      return "BigInt{" +
          "sign=" + sign +
          ", mag=" + Arrays.toString(mag) +
          '}';
    else
      return toString();
  }

  //------------------------------------------------------------------------------------------------
  //endregion

  //region Comparison
  //------------------------------------------------------------------------------------------------

  @Override
  public int compareTo(BigInt val) {
    if (this.sign == val.sign) {
      switch (sign) {
        case 1:
          return compareMagnitude(val);
        case -1:
          return val.compareMagnitude(this);
        default:
          return 0;
      }
    }
    return sign > val.sign ? 1 : -1;
  }

  private int compareMagnitude(BigInt val) {
    return compareMagnitude(this.mag, val.mag);
  }

  private static int compareMagnitude(int[] x, int[] y) {
    int xlen = x.length;
    int ylen = y.length;

    if (xlen < ylen)
      return -1;
    if (xlen > ylen)
      return 1;
    for (int i = 0; i < xlen; ++i) {
      long a = Integer.toUnsignedLong(x[i]);
      long b = Integer.toUnsignedLong(y[i]);
      if (a < b) {
        return -1;
      } else if (a > b) {
        return 1;
      }
    }
    return 0;
  }

  //------------------------------------------------------------------------------------------------
  //endregion

  //region Unary operators
  //------------------------------------------------------------------------------------------------

  public int getSign() {
    return sign;
  }

  public BigInt abs() {
    return (sign >= 0 ? this : this.negate());
  }

  public BigInt negate() {
    return new BigInt(this.mag, (byte) -this.sign);
  }

  public BigInt increment() {
    if (this.compareTo(ZERO) == 0)
      return ONE;
    if (this.compareTo(NEGATIVE_ONE) == 0)
      return ZERO;

    if (this.sign == -1)
      return new BigInt(decrement(mag), sign);
    else
      return new BigInt(increment(mag), sign);
  }

  private static int[] increment(int[] val) {
    boolean overflow = true;
    for (int i = val.length - 1; i >= 0 && overflow; --i) {
      if (val[i] == -1) {
        val[i] = 0;
      } else {
        val[i]++;
        overflow = false;
      }
    }
    if (overflow) {
      val = new int[val.length + 1];
      val[0] = 1;
    }
    return val;
  }

  public BigInt decrement() {
    if (this.compareTo(ZERO) == 0)
      return NEGATIVE_ONE;
    else if (this.compareTo(ONE) == 0)
      return ZERO;

    if (this.sign == -1)
      return new BigInt(increment(mag), sign);
    else
      return new BigInt(decrement(mag), sign);
  }

  private static int[] decrement(int[] val) {
    int i = val.length - 1;
    while (val[i] == 0) {
      val[i--] = -1;
    }
    val[i]--;
    return val;
  }

  //------------------------------------------------------------------------------------------------
  //endregion

  //region Addition and Subtraction
  //------------------------------------------------------------------------------------------------

  public BigInt add(BigInt val) {
    if (this.sign == 0)
      return val;
    else if (val.sign == 0)
      return this;

    if (this.sign == val.sign)
      return new BigInt(add(this.mag, val.mag), sign);

    byte cmp = (byte) compareMagnitude(val);
    if (cmp == 0)
      return ZERO;

    int[] resultMag;
    if (cmp > 0)
      resultMag = subtract(this.mag, val.mag);
    else
      resultMag = subtract(val.mag, this.mag);
    resultMag = stripLeadingZeros(resultMag);

    if (cmp == sign)
      return new BigInt(resultMag, (byte) 1);
    else
      return new BigInt(resultMag, (byte) -1);
  }

  private static int[] add(int[] x, int[] y) {
    // If x is shorter, swap the two arrays
    if (x.length < y.length) {
      int[] tmp = x;
      x = y;
      y = tmp;
    }

    int xi = x.length;
    int yi = y.length;
    int result[] = new int[xi];
    long sum = 0;
    if (yi == 1) {
      sum = (x[--xi] & LONG_MASK) + (y[0] & LONG_MASK);
      result[xi] = (int) sum;
    } else {
      // Add common parts of both numbers
      long carry = 0;
      while (yi > 0) {
        sum = (x[--xi] & LONG_MASK) + (y[--yi] & LONG_MASK) + carry;
        result[xi] = (int) (sum);
        carry = sum >>> 32;
      }
    }

    // Copy remainder of longer number while carry propagates
    boolean carry = (sum >>> 32 != 0);
    while (xi > 0 && carry) {
      carry = ((result[--xi] = x[xi] + 1) == 0);
    }

    // Copy remainder of longer number
    while (xi > 0)
      result[--xi] = x[xi];

    // Grow result if necessary
    if (carry) {
      int grown[] = new int[result.length + 1];
      System.arraycopy(result, 0, grown, 1, result.length);
      grown[0] = 1;
      return grown;
    }
    return result;
  }

  public BigInt subtract(BigInt val) {
    if (val.sign == 0)
      return this;
    else if (this.sign == 0)
      return val.negate();

    if (this.sign + val.sign == 0)
      return new BigInt(add(mag, val.mag), this.sign);

    byte cmp = (byte) compareMagnitude(val);
    if (cmp == 0)
      return ZERO;

    int[] resultMag;
    if (cmp > 0)
      resultMag = subtract(this.mag, val.mag);
    else
      resultMag = subtract(val.mag, this.mag);
    resultMag = stripLeadingZeros(resultMag);

    if (cmp == sign)
      return new BigInt(resultMag, (byte) 1);
    else
      return new BigInt(resultMag, (byte) -1);
  }

  private static int[] subtract(int[] large, int[] small) {
    int li = large.length;
    int[] result = new int[li];
    int si = small.length;
    long diff = 0;

    // Subtract common parts of both numbers
    while (si > 0) {
      diff = (large[--li] & LONG_MASK) - (small[--si] & LONG_MASK) + (diff >> 32);
      result[li] = (int) diff;
    }

    // Subtract remainder of longer number while borrow propagates
    boolean borrow = (diff >> 32) != 0;
    while (li > 0 && borrow) {
      result[--li] = large[li] - 1;
      borrow = result[li] == -1;
    }

    // Copy remainder of longer number
    while (li > 0)
      result[--li] = large[li];
    return result;
  }

  //------------------------------------------------------------------------------------------------
  //endregion

  //region Multiplication
  //------------------------------------------------------------------------------------------------

  public BigInt multiply(BigInt val) {
    if (this.sign == 0 || val.sign == 0)
      return ZERO;

    int xlen = mag.length;
    int ylen = val.mag.length;

    //if (xlen < KARATSUBA_THRESHOLD || ylen < KARATSUBA_THRESHOLD) {
    byte resultSign;
    if (sign == val.sign)
      resultSign = 1;
    else
      resultSign = -1;

    if (ylen == 1)
      return multiplyByInt(mag, val.mag[0], resultSign);
    if (xlen == 1)
      return multiplyByInt(val.mag, mag[0], resultSign);

    int[] result = multiplyToLen(mag, xlen, val.mag, ylen);
    result = stripLeadingZeros(result);
    return new BigInt(result, resultSign);
    //}
  }

  private static BigInt multiplyByInt(int[] x, int y, byte sign) {
    if (Integer.bitCount(y) == 1)
      return new BigInt(shiftLeft(x, Integer.numberOfTrailingZeros(y)), sign);

    int xlen = x.length;
    int[] result = new int[xlen + 1];
    long carry = 0;
    long yl = y & LONG_MASK;

    int ri = result.length - 1;
    for (int i = xlen - 1; i >= 0; --i) {
      long product = (x[i] & LONG_MASK) * yl + carry;
      result[ri--] = (int) (product);
      carry = product >>> 32;
    }
    if (carry == 0L) {
      result = Arrays.copyOfRange(result, 1, result.length);
    } else {
      result[ri] = (int) (carry);
    }

    return new BigInt(result, sign);
  }

  private static int[] multiplyToLen(int[] x, int xlen, int[] y, int ylen) {
    final int xi = xlen - 1;
    final int yi = ylen - 1;
    int[] z = new int[xlen + ylen];

    long carry = 0;
    for (int j = yi, k = xi + yi + 1; j >= 0; --j, --k) {
      long product = (x[xi] & LONG_MASK) * (y[j] & LONG_MASK) + carry;
      z[k] = (int) (product);
      carry = product >>> 32;
    }
    z[xi] = (int) carry;

    for (int i = xi - 1; i >= 0; --i) {
      carry = 0;
      for (int j = yi, k = yi + i + 1; j >= 0; --j, --k) {
        long product = (x[i] & LONG_MASK) * (y[j] & LONG_MASK) +
            (z[k] & LONG_MASK) + carry;
        z[k] = (int)(product);
        carry = product >>> 32;
      }
      z[i] = (int) carry;
    }
    return z;
  }

  //------------------------------------------------------------------------------------------------
  //endregion

  //region Division
  //------------------------------------------------------------------------------------------------

  public BigInt divide(BigInt val) {
    int cmp = this.abs().compareTo(val.abs());
    if (cmp == -1)
      return ZERO;
    else if (cmp == 0)
      return ONE;

    if (val.compareTo(ZERO) == 0)
      return ZERO;
    else if (val.compareTo(ONE) == 0)
      return this;
    else if (val.compareTo(NEGATIVE_ONE) == 0)
      return this.negate();

    BigInt result = this.abs().divideImpl(val.abs());
    result.mag = stripLeadingZeros(result.mag);
    if (result.mag.length >= MAX_MAG_LENGTH)
      result.checkRange();
    result.sign = (byte)(this.sign * val.sign);
    return result;
  }

  private BigInt divideImpl(BigInt y) {
    BigInt dividend = new BigInt(this);
    BigInt divisor = new BigInt(y);
    BigInt quotient = ZERO;
    int k = 0;
    while (dividend.compareTo(divisor) != -1) {
      divisor = divisor.shiftLeft(1);
      k++;
    }

    while (k-- > 0) {
      divisor = divisor.shiftRight(1);
      int cmp = dividend.compareTo(divisor);
      if (cmp >= 0) {
        dividend = dividend.subtract(divisor);
        quotient = quotient.shiftLeft(1).increment();
      } else {
        quotient = quotient.shiftLeft(1);
      }
    }
    return quotient;
  }

  private static int divideArrayByInt(int[] quot, int[] x,
      final int xlen, final int y) {
    long rem = 0;
    long yl = y & LONG_MASK;

    for (int i = xlen - 1; i >= 0; --i) {
      long temp = (rem << 32) | x[i] & LONG_MASK;
      long curr;
      if (temp >= 0) {
        curr = temp / yl;
        rem = temp % yl;
      } else {
        // Make dividend positive
        long aPos = temp >>> 1;
        long bPos = y >>> 1;
        curr = aPos / bPos;
        rem = ((aPos % bPos) << 1) + (temp & 1);
        if ((y & 1) != 0) {
          // Odd divisor
          if (curr <= rem) {
            rem -= curr;
          } else {
            if (curr - rem <= yl) {
              rem += yl - curr;
              curr -= 1;
            } else {
              rem += (yl << 1) - curr;
              curr -= 2;
            }
          }
        }
      }
      quot[i] = (int)(curr & LONG_MASK);
    }
    return (int) rem;
  }

  //------------------------------------------------------------------------------------------------
  //endregion

  //region Shifts
  //------------------------------------------------------------------------------------------------

  public BigInt shiftLeft(int n) {
    if (sign == 0)
      return ZERO;
    if (n > 0) {
      return new BigInt(shiftLeft(mag, n), sign);
    } else if (n == 0) {
      return this;
    } else {
      return shiftRightImpl(-n);
    }
  }

  private static int[] shiftLeft(int[] mag, int n) {
    if (mag.length == 0)
      return mag;

    int nInts = n >>> 5;
    int nBits = n & 0x1f;
    int magLen = mag.length;
    int newMag[] = null;

    if (nBits == 0) {
      newMag = new int[magLen + nInts];
      System.arraycopy(mag, 0, newMag, 0, magLen);
    } else {
      int i = 0;
      int nBits2 = 32 - nBits;
      int highBits = mag[0] >>> nBits2;
      if (highBits != 0) {
        newMag = new int[magLen + nInts + 1];
        newMag[i++] = highBits;
      } else {
        newMag = new int[magLen + nInts];
      }
      int j = 0;
      while (j < magLen-1)
        newMag[i++] = mag[j++] << nBits | mag[j] >>> nBits2;
      newMag[i] = mag[j] << nBits;
    }
    return newMag;
  }

  public BigInt shiftRight(int n) {
    if (sign == 0)
      return ZERO;
    if (n > 0) {
      return shiftRightImpl(n);
    } else if (n == 0) {
      return this;
    } else {
      // Possible int overflow in {@code -n} is not a trouble,
      // because shiftLeft considers its argument unsigned
      return new BigInt(shiftLeft(mag, -n), sign);
    }
  }

  private static int[] shiftRight(int[] mag, int n) {
    return new BigInt(mag, (byte) 1).shiftRightImpl(n).mag;
  }

  private BigInt shiftRightImpl(int n) {
    int nInts = n >>> 5;
    int nBits = n & 0x1f;
    int magLen = mag.length;
    int newMag[] = null;

    // Special case: entire contents shifted off the end
    if (nInts >= magLen)
      return (sign >= 0 ? ZERO : NEGATIVE_ONE);

    if (nBits == 0) {
      int newMagLen = magLen - nInts;
      newMag = Arrays.copyOf(mag, newMagLen);
    } else {
      int i = 0;
      int highBits = mag[0] >>> nBits;
      if (highBits != 0) {
        newMag = new int[magLen - nInts];
        newMag[i++] = highBits;
      } else {
        newMag = new int[magLen - nInts -1];
      }

      int nBits2 = 32 - nBits;
      int j=0;
      while (j < magLen - nInts - 1)
        newMag[i++] = (mag[j++] << nBits2) | (mag[j] >>> nBits);
    }

    if (sign < 0) {
      // Find out whether any one-bits were shifted off the end.
      boolean onesLost = false;
      for (int i=magLen-1, j=magLen-nInts; i >= j && !onesLost; i--)
        onesLost = (mag[i] != 0);
      if (!onesLost && nBits != 0)
        onesLost = (mag[magLen - nInts - 1] << (32 - nBits) != 0);

      if (onesLost)
        newMag = increment(newMag);
    }
    return new BigInt(newMag, sign);
  }

  //------------------------------------------------------------------------------------------------
  //endregion

  //region Private utility functions for construction and conversion
  //------------------------------------------------------------------------------------------------

  private void checkRange() {
    if (mag.length > MAX_MAG_LENGTH ||
        mag.length == MAX_MAG_LENGTH && mag[0] < 0)
      reportOverflow();
  }

  private static void checkRange(int[] mag) {
    if (mag.length > MAX_MAG_LENGTH ||
        mag.length == MAX_MAG_LENGTH && mag[0] < 0)
      reportOverflow();
  }

  private static void reportOverflow() {
    throw new ArithmeticException("BigInteger would overflow supported range");
  }

  private static void destructiveMulAdd(int[] x, int y, int z) {
    // Perform the multiplication word by word
    long yl = y & LONG_MASK;
    long zl = z & LONG_MASK;
    int len = x.length;

    long product;
    long carry = 0;
    for (int i = len - 1; i >= 0; --i) {
      product = yl * (x[i] & LONG_MASK) + carry;
      x[i] = (int) product;
      carry = product >>> 32;
    }

    // Perform the addition
    long sum = (x[len-1] & LONG_MASK) + zl;
    x[len-1] = (int)sum;
    carry = sum >>> 32;
    for (int i = len-2; i >= 0; i--) {
      sum = (x[i] & LONG_MASK) + carry;
      x[i] = (int)sum;
      carry = sum >>> 32;
    }
  }

  /**
   * Strips leading zero
   *
   * @param val Original int array
   * @return Int array stripped of leading zeros
   */
  private static int[] stripLeadingZeros(int[] val) {
    final int len = val.length;
    int keep;

    // Find first nonzero int
    for (keep = 0; keep < len && val[keep] == 0; ++keep);
    return Arrays.copyOfRange(val, keep, len);
  }

  /**
   * Converts a negative array to positive through 2's complement
   *
   * @param val Array representing a negative 2's complement number.
   * @return Unsigned array whose value is -val.
   */
  private static int[] makePositive(int[] val) {
    final int len = val.length;
    int keep, k;

    // Find first non-sign (0xffffffff) int
    for (keep = 0; keep < len && val[keep] == -1; ++keep);

    // Allocate output array
    // Allocate one extra output byte if all non-sign bytes = 0x00
    for (k = keep; k < len && val[k] == 0; ++k);
    int extraInt = (k == len) ? 1 : 0;
    int[] result = new int[len - keep + extraInt];

    // Copy 1's complement to output
    for (int i = keep; i < len; ++i)
      result[i - keep + extraInt] = ~val[i];

    // Add 1 to 1's complement to get 2's complement
    for (int i = result.length - 1; ++result[i] == 0; --i);

    return result;
  }

  //------------------------------------------------------------------------------------------------
  //endregion
}
