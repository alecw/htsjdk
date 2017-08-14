/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package htsjdk.samtools.reference;

import htsjdk.samtools.SAMException;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.util.IOUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.MatchResult;

/**
 * Reads/writes a fasta index file (.fai), as generated by `samtools faidx`.
 */
public class FastaSequenceIndex implements Iterable<FastaSequenceIndexEntry> {
    /**
     * Store the entries.  Use a LinkedHashMap for consistent iteration in insertion order.
     */
    private final Map<String,FastaSequenceIndexEntry> sequenceEntries = new LinkedHashMap<String,FastaSequenceIndexEntry>();

    /**
     * Build a sequence index from the specified file.
     * @param indexFile File to open.
     * @throws FileNotFoundException if the index file cannot be found.
     */
    public FastaSequenceIndex( File indexFile ) {
        this(IOUtil.toPath(indexFile));
    }

    /**
     * Build a sequence index from the specified file.
     * @param indexFile File to open.
     * @throws FileNotFoundException if the index file cannot be found.
     */
    public FastaSequenceIndex( Path indexFile ) {
        IOUtil.assertFileIsReadable(indexFile);
        parseIndexFile(indexFile);
    }

    /**
     * Empty, protected constructor for unit testing.
     */
    protected FastaSequenceIndex() {}

    /**
     * Add a new index entry to the list.  Protected for unit testing.
     * @param indexEntry New index entry to add.
     */
    protected void add(FastaSequenceIndexEntry indexEntry) {
        final FastaSequenceIndexEntry ret = sequenceEntries.put(indexEntry.getContig(),indexEntry);
        if (ret != null) {
            throw new SAMException("Contig '" + indexEntry.getContig() + "' already exists in fasta index.");
        }
    }

    /**
     * Renames the existing index entry to the new index entry with the specified name.
     * @param entry entry to update.
     * @param newName New name for the index entry.
     */
    protected void rename(FastaSequenceIndexEntry entry,String newName) {
        sequenceEntries.remove(entry.getContig());
        entry.setContig(newName);
        add(entry);
    }

    /**
     * Compare two FastaSequenceIndex objects for equality.
     * @param other Another FastaSequenceIndex to compare
     * @return True if index has the same entries as other instance, in the same order
     */
    public boolean equals(Object other) {
        if(!(other instanceof FastaSequenceIndex))
            return false;

        if (this == other) return true;

        FastaSequenceIndex otherIndex = (FastaSequenceIndex)other;
        if(this.size() != otherIndex.size())
            return false;

        Iterator<FastaSequenceIndexEntry> iter = this.iterator();
        Iterator<FastaSequenceIndexEntry> otherIter = otherIndex.iterator();
        while (iter.hasNext()) {
            if (!otherIter.hasNext())
                return false;
            if (!iter.next().equals(otherIter.next()))
                return false;
        }
        return true;
    }

    /**
     * Parse the contents of an index file, caching the results internally.
     * @param indexFile File to parse.
     * @throws IOException Thrown if file could not be opened.
     */
    private void parseIndexFile(Path indexFile) {
        try {
            Scanner scanner = new Scanner(indexFile);
            int sequenceIndex = 0;
            while( scanner.hasNext() ) {
                // Tokenize and validate the index line.
                String result = scanner.findInLine("(.+)\\t+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
                if( result == null )
                    throw new SAMException("Found invalid line in index file:" + scanner.nextLine());
                MatchResult tokens = scanner.match();
                if( tokens.groupCount() != 5 )
                    throw new SAMException("Found invalid line in index file:" + scanner.nextLine());

                // Skip past the line separator
                scanner.nextLine();

                // Parse the index line.
                String contig = tokens.group(1);
                long size = Long.valueOf(tokens.group(2));
                long location = Long.valueOf(tokens.group(3));
                int basesPerLine = Integer.valueOf(tokens.group(4));
                int bytesPerLine = Integer.valueOf(tokens.group(5));

                contig = SAMSequenceRecord.truncateSequenceName(contig);
                // Build sequence structure
                add(new FastaSequenceIndexEntry(contig,location,size,basesPerLine,bytesPerLine, sequenceIndex++) );
            }
            scanner.close();
        } catch (IOException e) {
            throw new SAMException("Fasta index file could not be opened: " + indexFile, e);

        }
    }

    /**
     * Writes this index to the specified path.
     *
     * @param indexFile index file to output the index in the .fai format
     *
     * @throws IOException if an IO error occurs.
     */
    public void write(final Path indexFile) throws IOException {
        try (final PrintStream writer = new PrintStream(Files.newOutputStream(indexFile))) {
            sequenceEntries.values().forEach(se ->
                    writer.println(String.join("\t",
                            se.getContig(),
                            String.valueOf(se.getSize()),
                            String.valueOf(se.getLocation()),
                            String.valueOf(se.getBasesPerLine()),
                            String.valueOf(se.getBytesPerLine()))
                    )
            );
        }
    }

    /**
     * Does the given contig name have a corresponding entry?
     * @param contigName The contig name for which to search.
     * @return True if contig name is present; false otherwise.
     */
    public boolean hasIndexEntry( String contigName ) {
        return sequenceEntries.containsKey(contigName);
    }

    /**
     * Retrieve the index entry associated with the given contig.
     * @param contigName Name of the contig for which to search.
     * @return Index entry associated with the given contig.
     * @throws SAMException if the associated index entry can't be found.
     */
    public FastaSequenceIndexEntry getIndexEntry( String contigName ) {
        if( !hasIndexEntry(contigName) )
            throw new SAMException("Unable to find entry for contig: " + contigName);

        return sequenceEntries.get(contigName);
    }

    /**
     * Creates an iterator which can iterate through all entries in a fasta index.
     * @return iterator over all fasta index entries.
     */
    @Override
    public Iterator<FastaSequenceIndexEntry> iterator() {
        return sequenceEntries.values().iterator();
    }

    /**
     * Returns the number of elements in the index.
     * @return Number of elements in the index.
     */
    public int size() {
        return sequenceEntries.size();
    }
}

/**
 * Hold an individual entry in a fasta sequence index file.
 */
class FastaSequenceIndexEntry {
    private String contig;
    private long location;
    private long size;
    private int basesPerLine;
    private int bytesPerLine;
    private final int sequenceIndex;

    /**
     * Create a new entry with the given parameters.
     * @param contig Contig this entry represents.
     * @param location Location (byte coordinate) in the fasta file.
     * @param size The number of bases in the contig.
     * @param basesPerLine How many bases are on each line.
     * @param bytesPerLine How many bytes are on each line (includes newline characters).
     */
    public FastaSequenceIndexEntry( String contig,
                                    long location,
                                    long size,
                                    int basesPerLine,
                                    int bytesPerLine,
                                    int sequenceIndex) {
        this.contig = contig;
        this.location = location;
        this.size = size;
        this.basesPerLine = basesPerLine;
        this.bytesPerLine = bytesPerLine;
        this.sequenceIndex = sequenceIndex;
    }

    /**
     * Gets the contig associated with this entry.
     * @return String representation of the contig.
     */
    public String getContig() {
        return contig;
    }

    /**
     * Sometimes contigs need to be adjusted on-the-fly to
     * match sequence dictionary entries.  Provide that capability
     * to other classes w/i the package. 
     * @param contig New value for the contig.
     */
    protected void setContig(String contig) {
        this.contig = contig;
    }

    /**
     * Gets the location of this contig within the fasta.
     * @return seek position within the fasta.
     */
    public long getLocation() {
        return location;
    }

    /**
     * Gets the size, in bytes, of the data in the contig.
     * @return size of the contig bases in bytes.
     */
    public long getSize() {
        return size;
    }

    /**
     * Gets the number of bases in a given line.
     * @return Number of bases in the fasta line.
     */
    public int getBasesPerLine() {
        return basesPerLine;
    }

    /**
     * How many bytes (bases + whitespace) are consumed by the
     * given line?
     * @return Number of bytes in a line.
     */
    public int getBytesPerLine() {
        return bytesPerLine;
    }

    public int getSequenceIndex() {
        return sequenceIndex;
    }

    /**
     * For debugging.  Emit the contents of each contig line.
     * @return A string representation of the contig line.
     */
    public String toString() {
        return String.format("contig %s; location %d; size %d; basesPerLine %d; bytesPerLine %d", contig,
                                                                                                  location,
                                                                                                  size,
                                                                                                  basesPerLine,
                                                                                                  bytesPerLine );
    }

    /**
     * Compare this index entry to another index entry.
     * @param other another FastaSequenceIndexEntry
     * @return True if each has the same name, location, size, basesPerLine and bytesPerLine
     */
    public boolean equals(Object other) {
        if(!(other instanceof FastaSequenceIndexEntry))
            return false;

        if (this == other) return true;

        FastaSequenceIndexEntry otherEntry = (FastaSequenceIndexEntry)other;
        return (contig.equals(otherEntry.contig) && size == otherEntry.size && location == otherEntry.location
        && basesPerLine == otherEntry.basesPerLine && bytesPerLine == otherEntry.bytesPerLine);
    }

    /**
     * In general, we expect one entry per contig, so compute the hash based only on the contig.
     * @return A unique hash code representing this object.
     */
    public int hashCode() {
        return contig.hashCode();
    }
}
