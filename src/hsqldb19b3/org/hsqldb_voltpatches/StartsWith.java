/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.hsqldb_voltpatches;

import org.hsqldb_voltpatches.lib.StringUtil;
import org.hsqldb_voltpatches.types.Type;
import org.hsqldb_voltpatches.types.BinaryData;
import org.hsqldb_voltpatches.lib.HsqlByteArrayOutputStream;

/**
 * Reusable object for processing STARTS WITH queries.
 *
 */

class StartsWith {

    private final static BinaryData maxByteValue =
        new BinaryData(new byte[]{ -128 }, false);
    private char[]   cStartsWith;
    private int      iLen;
    private boolean  isNull;
    boolean          hasCollation;
    boolean          isVariable      = true;
    boolean          isBinary        = false;
    Type             dataType;

    StartsWith() {}

    void setParams(boolean collation) {
        hasCollation = collation;
    }

    private Object getStartsWith() {

        if (iLen == 0) {
            return isBinary ? BinaryData.zeroLengthBinary
                            : "";
        }

        StringBuffer              sb = null;
        HsqlByteArrayOutputStream os = null;

        if (isBinary) {
            os = new HsqlByteArrayOutputStream();
        } else {
            sb = new StringBuffer();
        }

        int i = 0;

        for (; i < iLen; i++) {
            if (isBinary) {
                os.writeByte(cStartsWith[i]);
            } else {
                sb.append(cStartsWith[i]);
            }
        }

        if (i == 0) {
            return null;
        }

        return isBinary ? new BinaryData(os.toByteArray(), false)
                        : sb.toString();
    }

    Boolean compare(Session session, Object o) {

        if (o == null) {
            return null;
        }

        if (isNull) {
            return null;
        }

        return compareAt(o, 0, 0, getLength(session, o, "")) ? Boolean.TRUE
                                                             : Boolean.FALSE;
    }

    char getChar(Object o, int i) {

        char c;

        if (isBinary) {
            c = (char) ((BinaryData) o).getBytes()[i];
        } else {
            c = ((String) o).charAt(i);
        }

        return c;
    }

    int getLength(SessionInterface session, Object o, String s) {

        int l;

        if (isBinary) {
            l = (int) ((BinaryData) o).length(session);
        } else {
            l = ((String) o).length();
        }

        return l;
    }

    private boolean compareAt(Object o, int i, int j, int jLen) {

        for (; i < iLen; i++) {
            if ((j >= jLen) || (cStartsWith[i] != getChar(o, j++))) {
                return false;
            }
        }

        return true;
    }

    void setPattern(Session session, Object pattern) {

        isNull = pattern == null;

        if (isNull) {
            return;
        }

        iLen           = 0;

        int l = getLength(session, pattern, "");

        cStartsWith        = new char[l];

        for (int i = 0; i < l; i++) {
            char c = getChar(pattern, i);
            cStartsWith[iLen++] = c;
        }
    }

    boolean isEquivalentToUnknownPredicate() {
        return isNull;
    }

    boolean isEquivalentToNotNullPredicate() {

        if (isVariable || isNull) {
            return false;
        }

        return true;
    }

    boolean isEquivalentToCharPredicate() {
        return !isVariable;
    }

    Object getRangeLow() {
        return getStartsWith();
    }

    Object getRangeHigh(Session session) {

        Object o = getStartsWith();

        if (o == null) {
            return null;
        }

        if (isBinary) {
            return new BinaryData(session, (BinaryData) o, maxByteValue);
        } else {
            return dataType.concat(session, o, "\uffff");
        }
    }

    public String describe(Session session) {

        StringBuffer sb = new StringBuffer();

        sb.append(super.toString()).append("[\n");
        sb.append("isNull=").append(isNull).append('\n');

        sb.append("iLen=").append(iLen).append('\n');
        sb.append("cStartsWith=");
        sb.append(StringUtil.arrayToString(cStartsWith));
        sb.append(']');

        return sb.toString();
    }
}
