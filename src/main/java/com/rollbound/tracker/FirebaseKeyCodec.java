package com.rollbound.tracker;

/**
 * Encodes arbitrary display strings for use as Firebase Realtime Database object keys.
 *
 * Firebase keys may not contain '.', '$', '#', '[', ']' or '/'. '%' is encoded too so
 * the transformation is reversible without collisions with literal escape sequences.
 */
public final class FirebaseKeyCodec
{
    private FirebaseKeyCodec() {}

    public static String encode(String value)
    {
        if (value == null)
        {
            return "";
        }

        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            switch (c)
            {
                case '%': out.append("%25"); break;
                case '.': out.append("%2E"); break;
                case '$': out.append("%24"); break;
                case '#': out.append("%23"); break;
                case '[': out.append("%5B"); break;
                case ']': out.append("%5D"); break;
                case '/': out.append("%2F"); break;
                default:
                    if (c <= 0x1F || c == 0x7F)
                    {
                        out.append(String.format("%%%02X", (int) c));
                    }
                    else
                    {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }
}
