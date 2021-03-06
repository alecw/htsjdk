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
package htsjdk.samtools.cram.build;

import htsjdk.samtools.cram.common.MutableInt;
import htsjdk.samtools.cram.compression.ExternalCompressor;
import htsjdk.samtools.cram.encoding.*;
import htsjdk.samtools.cram.encoding.core.CanonicalHuffmanIntegerEncoding;
import htsjdk.samtools.cram.encoding.external.*;
import htsjdk.samtools.cram.compression.rans.RANS;
import htsjdk.samtools.cram.encoding.readfeatures.ReadFeature;
import htsjdk.samtools.cram.encoding.readfeatures.Substitution;
import htsjdk.samtools.cram.structure.CompressionHeader;
import htsjdk.samtools.cram.structure.CramCompressionRecord;
import htsjdk.samtools.cram.structure.DataSeries;
import htsjdk.samtools.cram.structure.EncodingParams;
import htsjdk.samtools.cram.structure.ReadTag;
import htsjdk.samtools.cram.structure.SubstitutionMatrix;
import htsjdk.samtools.util.RuntimeIOException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A class responsible for decisions about which encodings to use for a given set of records.
 * This particular version relies heavily on GZIP and RANS for better compression.
 */
public class CompressionHeaderFactory {
    public static final int BYTE_SPACE_SIZE = 256;
    public static final int ALL_BYTES_USED = -1;

    // a parameter for Huffman encoding, so we don't have to re-construct on each call
    private static final int[] singleZero = new int[] { 0 };
    private final Map<Integer, EncodingDetails> bestEncodings = new HashMap<>();
    private final ByteArrayOutputStream baosForTagValues = new ByteArrayOutputStream(1024 * 1024);

    /**
     * Decides on compression methods to use for the given records.
     *
     * @param records
     *            the data to be compressed
     * @param coordinateSorted
     *            if true the records are assumed to be sorted by alignment
     *            position
     * @return {@link htsjdk.samtools.cram.structure.CompressionHeader} object
     *         describing the encoding chosen for the data
     */
    public CompressionHeader build(final List<CramCompressionRecord> records, final boolean coordinateSorted) {

        final CompressionHeaderBuilder builder = new CompressionHeaderBuilder(coordinateSorted);

        builder.addExternalRansOrderZeroEncoding(DataSeries.AP_AlignmentPositionOffset);
        builder.addExternalRansOrderOneEncoding(DataSeries.BA_Base);
        // BB is not used
        builder.addExternalRansOrderOneEncoding(DataSeries.BF_BitFlags);
        builder.addExternalGzipEncoding(DataSeries.BS_BaseSubstitutionCode);
        builder.addExternalRansOrderOneEncoding(DataSeries.CF_CompressionBitFlags);
        builder.addExternalGzipEncoding(DataSeries.DL_DeletionLength);
        builder.addExternalGzipEncoding(DataSeries.FC_FeatureCode);
        builder.addExternalGzipEncoding(DataSeries.FN_NumberOfReadFeatures);
        builder.addExternalGzipEncoding(DataSeries.FP_FeaturePosition);
        builder.addExternalGzipEncoding(DataSeries.HC_HardClip);
        builder.addExternalByteArrayStopTabGzipEncoding(DataSeries.IN_Insertion);
        builder.addExternalGzipEncoding(DataSeries.MF_MateBitFlags);
        builder.addExternalGzipEncoding(DataSeries.MQ_MappingQualityScore);
        builder.addExternalGzipEncoding(DataSeries.NF_RecordsToNextFragment);
        builder.addExternalGzipEncoding(DataSeries.NP_NextFragmentAlignmentStart);
        builder.addExternalRansOrderOneEncoding(DataSeries.NS_NextFragmentReferenceSequenceID);
        builder.addExternalGzipEncoding(DataSeries.PD_padding);
        // QQ is not used
        builder.addExternalRansOrderOneEncoding(DataSeries.QS_QualityScore);
        builder.addExternalRansOrderOneEncoding(DataSeries.RG_ReadGroup);
        builder.addExternalRansOrderZeroEncoding(DataSeries.RI_RefId);
        builder.addExternalRansOrderOneEncoding(DataSeries.RL_ReadLength);
        builder.addExternalByteArrayStopTabGzipEncoding(DataSeries.RN_ReadName);
        builder.addExternalGzipEncoding(DataSeries.RS_RefSkip);
        builder.addExternalByteArrayStopTabGzipEncoding(DataSeries.SC_SoftClip);
        builder.addExternalGzipEncoding(DataSeries.TC_TagCount);
        builder.addExternalGzipEncoding(DataSeries.TL_TagIdList);
        builder.addExternalGzipEncoding(DataSeries.TN_TagNameAndType);
        builder.addExternalRansOrderOneEncoding(DataSeries.TS_InsertSize);

        builder.setTagIdDictionary(buildTagIdDictionary(records));

        buildTagEncodings(records, builder);

        final SubstitutionMatrix substitutionMatrix = new SubstitutionMatrix(records);
        updateSubstitutionCodes(records, substitutionMatrix);
        builder.setSubstitutionMatrix(substitutionMatrix);
        return builder.getHeader();
    }

    /**
     * Iterate over the records and for each tag found come up with an encoding.
     * Tag encodings are registered via the builder.
     *
     * @param records
     *            CRAM records holding the tags to be encoded
     * @param builder
     *            compression header builder to register encodings
     */
    private void buildTagEncodings(final List<CramCompressionRecord> records, final CompressionHeaderBuilder builder) {
        final Set<Integer> tagIdSet = new HashSet<>();

        for (final CramCompressionRecord record : records) {
            if (record.tags == null || record.tags.length == 0) {
                continue;
            }

            for (final ReadTag tag : record.tags) {
                tagIdSet.add(tag.keyType3BytesAsInt);
            }
        }

        for (final int tagId : tagIdSet) {
            if (bestEncodings.containsKey(tagId)) {
                builder.addTagEncoding(tagId, bestEncodings.get(tagId));
            } else {
                final EncodingDetails e = buildEncodingForTag(records, tagId);
                builder.addTagEncoding(tagId, e);
                bestEncodings.put(tagId, e);
            }
        }
    }

    /**
     * Given the records update the substitution matrix with actual substitution
     * codes.
     *
     * @param records
     *            CRAM records
     * @param substitutionMatrix
     *            the matrix to be updated
     */
    static void updateSubstitutionCodes(final List<CramCompressionRecord> records,
                                                final SubstitutionMatrix substitutionMatrix) {
        for (final CramCompressionRecord record : records) {
            if (record.readFeatures != null) {
                for (final ReadFeature recordFeature : record.readFeatures) {
                    if (recordFeature.getOperator() == Substitution.operator) {
                        final Substitution substitution = ((Substitution) recordFeature);
                        if (substitution.getCode() == Substitution.NO_CODE) {
                            final byte refBase = substitution.getReferenceBase();
                            final byte base = substitution.getBase();
                            substitution.setCode(substitutionMatrix.code(refBase, base));
                        }
                    }
                }
            }
        }
    }

    /**
     * Build a dictionary of tag ids.
     *
     * @param records
     *            records holding the tags
     * @return a 3D byte array: a set of unique lists of tag ids.
     */
    private static byte[][][] buildTagIdDictionary(final List<CramCompressionRecord> records) {
        final Comparator<ReadTag> comparator = new Comparator<ReadTag>() {

            @Override
            public int compare(final ReadTag o1, final ReadTag o2) {
                return o1.keyType3BytesAsInt - o2.keyType3BytesAsInt;
            }
        };

        final Comparator<byte[]> baComparator = new Comparator<byte[]>() {

            @Override
            public int compare(final byte[] o1, final byte[] o2) {
                if (o1.length - o2.length != 0) {
                    return o1.length - o2.length;
                }

                for (int i = 0; i < o1.length; i++) {
                    if (o1[i] != o2[i]) {
                        return o1[i] - o2[i];
                    }
                }

                return 0;
            }
        };

        final Map<byte[], MutableInt> map = new TreeMap<>(baComparator);
        final MutableInt noTagCounter = new MutableInt();
        map.put(new byte[0], noTagCounter);
        for (final CramCompressionRecord record : records) {
            if (record.tags == null) {
                noTagCounter.value++;
                record.tagIdsIndex = noTagCounter;
                continue;
            }

            Arrays.sort(record.tags, comparator);
            final byte[] tagIds = new byte[record.tags.length * 3];

            int tagIndex = 0;
            for (int i = 0; i < record.tags.length; i++) {
                tagIds[i * 3] = (byte) record.tags[tagIndex].keyType3Bytes.charAt(0);
                tagIds[i * 3 + 1] = (byte) record.tags[tagIndex].keyType3Bytes.charAt(1);
                tagIds[i * 3 + 2] = (byte) record.tags[tagIndex].keyType3Bytes.charAt(2);
                tagIndex++;
            }

            MutableInt count = map.get(tagIds);
            if (count == null) {
                count = new MutableInt();
                map.put(tagIds, count);
            }
            count.value++;
            record.tagIdsIndex = count;
        }

        final byte[][][] dictionary = new byte[map.size()][][];
        int i = 0;
        for (final byte[] idsAsBytes : map.keySet()) {
            final int nofIds = idsAsBytes.length / 3;
            dictionary[i] = new byte[nofIds][];
            for (int j = 0; j < idsAsBytes.length;) {
                final int idIndex = j / 3;
                dictionary[i][idIndex] = new byte[3];
                dictionary[i][idIndex][0] = idsAsBytes[j++];
                dictionary[i][idIndex][1] = idsAsBytes[j++];
                dictionary[i][idIndex][2] = idsAsBytes[j++];
            }
            map.get(idsAsBytes).value = i++;
        }
        return dictionary;
    }

    /**
     * Tag id is and integer where the first byte is its type and the other 2
     * bytes represent the name. For example 'OQZ', where 'OQ' stands for
     * original quality score tag and 'Z' stands for string type.
     *
     * @param tagID
     *            a 3 byte tag id stored in an int
     * @return tag type, the lowest byte in the tag id
     */
    static byte getTagType(final int tagID) {
        return (byte) (tagID & 0xFF);
    }

    static ExternalCompressor getBestExternalCompressor(final byte[] data) {
        final ExternalCompressor gzip = ExternalCompressor.createGZIP();
        final int gzipLen = gzip.compress(data).length;

        final ExternalCompressor rans0 = ExternalCompressor.createRANS(RANS.ORDER.ZERO);
        final int rans0Len = rans0.compress(data).length;

        final ExternalCompressor rans1 = ExternalCompressor.createRANS(RANS.ORDER.ONE);
        final int rans1Len = rans1.compress(data).length;

        // find the best of general purpose codecs:
        final int minLen = Math.min(gzipLen, Math.min(rans0Len, rans1Len));
        if (minLen == rans0Len) {
            return rans0;
        } else if (minLen == rans1Len) {
            return rans1;
        } else {
            return gzip;
        }
    }

    byte[] getDataForTag(final List<CramCompressionRecord> records, final int tagID) {
        baosForTagValues.reset();

        for (final CramCompressionRecord record : records) {
            if (record.tags == null) {
                continue;
            }

            for (final ReadTag tag : record.tags) {
                if (tag.keyType3BytesAsInt != tagID) {
                    continue;
                }
                final byte[] valueBytes = tag.getValueAsByteArray();
                try {
                    baosForTagValues.write(valueBytes);
                } catch (final IOException e) {
                    throw new RuntimeIOException(e);
                }
            }
        }

        return baosForTagValues.toByteArray();
    }

    static ByteSizeRange getByteSizeRangeOfTagValues(final List<CramCompressionRecord> records, final int tagID) {
        final byte type = getTagType(tagID);
        final ByteSizeRange stats = new ByteSizeRange();
        for (final CramCompressionRecord record : records) {
            if (record.tags == null) {
                continue;
            }

            for (final ReadTag tag : record.tags) {
                if (tag.keyType3BytesAsInt != tagID) {
                    continue;
                }
                final int size = getTagValueByteSize(type, tag.getValue());
                if (stats.min > size)
                    stats.min = size;
                if (stats.max < size)
                    stats.max = size;
            }
        }
        return stats;
    }

    /**
     * Find a byte value never mentioned in the array
     * @param array bytes
     * @return byte value or -1 if the array contains all possible byte values.
     */
    static int getUnusedByte(final byte[] array) {
        final byte[] usage = new byte[BYTE_SPACE_SIZE];
        for (final byte b : array) {
            usage[0xFF & b] = 1;
        }

        for (int i = 0; i < usage.length; i++) {
            if (usage[i] == 0)
                return i;
        }
        return ALL_BYTES_USED;
    }

    static class ByteSizeRange {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
    }

    /**
     * A combination of external compressor and encoding params. This is all
     * that is needed to encode a data series.
     */
    private static class EncodingDetails {
        ExternalCompressor compressor;
        EncodingParams params;
    }

    /**
     * Used by buildEncodingForTag to create a ByteArrayLenEncoding with CanonicalHuffmanIntegerEncoding and
     * ExternalByteArrayEncoding sub-encodings
     *
     * @param tagValueSize the size of the tag value, to be Huffman encoded
     * @param tagID the ID of the tag
     * @return EncodingParams a complete description of the result Encoding
     */
    private EncodingParams buildTagEncodingForSize(final int tagValueSize, final int tagID) {
        return new ByteArrayLenEncoding(
                new CanonicalHuffmanIntegerEncoding(new int[] { tagValueSize }, singleZero),
                new ExternalByteArrayEncoding(tagID)).toParam();
    }

    /**
     * Build an encoding for a specific tag for given records.
     *
     * @param records CRAM records holding the tags
     * @param tagID an integer id of the tag
     * @return an encoding for the tag
     */
    private EncodingDetails buildEncodingForTag(final List<CramCompressionRecord> records, final int tagID) {
        final EncodingDetails details = new EncodingDetails();
        final byte[] data = getDataForTag(records, tagID);

        details.compressor = getBestExternalCompressor(data);

        final byte type = getTagType(tagID);
        switch (type) {
            case 'A':
            case 'c':
            case 'C':
                details.params = buildTagEncodingForSize(1, tagID);
                return details;

            case 'I':
            case 'i':
            case 'f':
                details.params = buildTagEncodingForSize(4, tagID);
                return details;

            case 's':
            case 'S':
                details.params = buildTagEncodingForSize(2, tagID);
                return details;

            case 'Z':
            case 'B':
                final ByteSizeRange stats = getByteSizeRangeOfTagValues(records, tagID);
                final boolean singleSize = stats.min == stats.max;
                if (singleSize) {
                    details.params = buildTagEncodingForSize(stats.min, tagID);
                    return details;
                }

                if (type == 'Z') {
                    details.params = new ByteArrayStopEncoding((byte) '\t', tagID).toParam();
                    return details;
                }

                final int minSize_threshold_ForByteArrayStopEncoding = 100;
                if (stats.min > minSize_threshold_ForByteArrayStopEncoding) {
                    final int unusedByte = getUnusedByte(data);
                    if (unusedByte > ALL_BYTES_USED) {
                        details.params = new ByteArrayStopEncoding((byte) unusedByte, tagID).toParam();
                        return details;
                    }
                }

                details.params = new ByteArrayLenEncoding(
                        new ExternalIntegerEncoding(tagID),
                        new ExternalByteArrayEncoding(tagID)).toParam();
                return details;
            default:
                throw new IllegalArgumentException("Unknown tag type: " + (char) type);
        }
    }

    /**
     * A helper class to build
     * {@link htsjdk.samtools.cram.structure.CompressionHeader} object.
     */
    private static class CompressionHeaderBuilder {
        private final CompressionHeader header;

        CompressionHeaderBuilder(final boolean coordinateSorted) {
            header = new CompressionHeader();
            header.externalIds = new ArrayList<>();
            header.tMap = new TreeMap<>();

            header.encodingMap = new TreeMap<>();
            header.APDelta = coordinateSorted;
        }

        CompressionHeader getHeader() {
            return header;
        }

        private void addExternalEncoding(final DataSeries dataSeries,
                                         final EncodingParams params,
                                         final ExternalCompressor compressor) {
            header.externalIds.add(dataSeries.getExternalBlockContentId());
            header.externalCompressors.put(dataSeries.getExternalBlockContentId(), compressor);
            header.encodingMap.put(dataSeries, params);
        }

        private void addExternalByteArrayStopTabGzipEncoding(final DataSeries dataSeries) {
            addExternalEncoding(dataSeries,
                    new ByteArrayStopEncoding((byte) '\t', dataSeries.getExternalBlockContentId()).toParam(),
                    ExternalCompressor.createGZIP());
        }

        private void addExternalEncoding(final DataSeries dataSeries, final ExternalCompressor compressor) {
            // we need a concrete type; the choice of Byte is arbitrary.
            // params are equal for all External Encoding value types
            final EncodingParams params = new ExternalByteEncoding(dataSeries.getExternalBlockContentId()).toParam();
            addExternalEncoding(dataSeries, params, compressor);
        }

        private void addExternalGzipEncoding(final DataSeries dataSeries) {
            addExternalEncoding(dataSeries, ExternalCompressor.createGZIP());
        }

        private void addExternalRansOrderOneEncoding(final DataSeries dataSeries) {
            addExternalEncoding(dataSeries, ExternalCompressor.createRANS(RANS.ORDER.ONE));
        }

        private void addExternalRansOrderZeroEncoding(final DataSeries dataSeries) {
            addExternalEncoding(dataSeries, ExternalCompressor.createRANS(RANS.ORDER.ZERO));
        }

        void addTagEncoding(final int tagId, final EncodingDetails encodingDetails) {
            header.externalIds.add(tagId);
            header.externalCompressors.put(tagId, encodingDetails.compressor);
            header.tMap.put(tagId, encodingDetails.params);
        }

        void setTagIdDictionary(final byte[][][] dictionary) {
            header.dictionary = dictionary;
        }

        void setSubstitutionMatrix(final SubstitutionMatrix substitutionMatrix) {
            header.substitutionMatrix = substitutionMatrix;
        }
    }

    /**
     * Calculate byte size of a tag value based on it's type and value class
     * @param type tag type, like 'A' or 'i'
     * @param value object representing the tag value
     * @return number of bytes used for the tag value
     */
    static int getTagValueByteSize(final byte type, final Object value) {
        switch (type) {
            case 'A':
                return 1;
            case 'I':
                return 4;
            case 'i':
                return 4;
            case 's':
                return 2;
            case 'S':
                return 2;
            case 'c':
                return 1;
            case 'C':
                return 1;
            case 'f':
                return 4;
            case 'Z':
                return ((String) value).length()+1;
            case 'B':
                if (value instanceof byte[])
                    return 1+ 4+ ((byte[]) value).length;
                if (value instanceof short[])
                    return 1+ 4+ ((short[]) value).length * 2;
                if (value instanceof int[])
                    return 1+ 4+ ((int[]) value).length * 4;
                if (value instanceof float[])
                    return 1+ 4+ ((float[]) value).length * 4;
                if (value instanceof long[])
                    return 1+ 4+ ((long[]) value).length * 4;

                throw new RuntimeException("Unknown tag array class: " + value.getClass());
            default:
                throw new RuntimeException("Unknown tag type: " + (char) type);
        }
    }
}
