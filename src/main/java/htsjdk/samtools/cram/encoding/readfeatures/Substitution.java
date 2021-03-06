/**
 * ****************************************************************************
 * Copyright 2013 EMBL-EBI
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ****************************************************************************
 */
package htsjdk.samtools.cram.encoding.readfeatures;

import java.io.Serializable;
import java.util.Objects;

/**
 * A substitution event captured in read coordinates. It is characterized by position in read, read base and reference base.
 * The class is also responsible for converting combinations of read base and reference base into a byte value (code).
 *
 * Both reference and read bases must be ACGTN only.
 */
public class Substitution implements Serializable, ReadFeature {
    public static final int NO_CODE = -1;

    /**
     * zero-based position in read
     */
    private int position;
    /**
     * The read base, allowed values are ACGTN.
     */
    private byte base = -1;
    /**
     * The reference sequence base matching the position of this substitution, allowed values are ACGTN.
     */
    private byte referenceBase = -1;
    /**
     * A byte value denoting combination of the read base and the reference base.
     */
    private byte code = NO_CODE;

    public Substitution() {
    }

    public Substitution(int position, byte base, byte referenceBase) {
        this.position = position;
        this.base = base;
        this.referenceBase = referenceBase;
    }

    public byte getCode() {
        return code;
    }

    public void setCode(final byte code) {
        this.code = code;
    }

    public static final byte operator = 'X';

    @Override
    public byte getOperator() {
        return operator;
    }

    @Override
    public int getPosition() {
        return position;
    }

    @Override
    public void setPosition(final int position) {
        this.position = position;
    }

    public byte getBase() {
        return base;
    }

    public void setBase(final byte base) {
        this.base = base;
    }

    public byte getReferenceBase() {
        return referenceBase;
    }

    public void setReferenceBase(final byte referenceBase) {
        this.referenceBase = referenceBase;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof Substitution))
            return false;

        final Substitution substitution = (Substitution) obj;

        if (position != substitution.position)
            return false;

        if ((code != substitution.code) & (code == NO_CODE || substitution.code == NO_CODE)) {
            return false;
        }

        if (code > NO_CODE && substitution.code > NO_CODE) {
            if (referenceBase != substitution.referenceBase) return false;
            if (base != substitution.base) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        if (code == NO_CODE) {
            return Objects.hash(position);
        }
        return Objects.hash(position, base, referenceBase);
    }

    @Override
    public String toString() {
        return String.valueOf((char) operator) + '@' + position + '\\' + (char) base + (char) referenceBase;
    }
}
