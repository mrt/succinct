package edu.berkeley.cs.succinct.streams;

import edu.berkeley.cs.succinct.SuccinctIndexedFile;
import edu.berkeley.cs.succinct.regex.parser.RegExParsingException;
import edu.berkeley.cs.succinct.util.Range;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.util.*;

public class SuccinctIndexedFileStream extends SuccinctFileStream implements SuccinctIndexedFile {

    protected transient int offsets[];

    /**
     * Constructor to map a file containing Succinct data structures via streams
     *
     * @param filePath Path of the file.
     * @throws IOException
     */
    public SuccinctIndexedFileStream(Path filePath) throws IOException {
        super(filePath);
        FSDataInputStream is = getStream(filePath);
        is.seek(endOfCoreStream);
        int len = is.readInt();
        offsets = new int[len];
        for(int i = 0; i < len; i++) {
            offsets[i] = is.readInt();
        }
    }


    public int searchOffset(int pos) {
        int sp = 0, ep = offsets.length - 1;
        int m;

        while (sp <= ep) {
            m = (sp + ep) / 2;
            if (offsets[m] == pos) {
                return m;
            } else if (pos < offsets[m]) {
                ep = m - 1;
            } else {
                sp = m + 1;
            }
        }

        return ep;
    }

    public int getNumRecords() {
        return offsets.length;
    }

    public byte[] getRecord(int i) {
        if(i >= offsets.length || i < 0) {
            throw new ArrayIndexOutOfBoundsException("Record does not exist: i = " + i);
        }
        int begOffset = offsets[i];
        int endOffset = (i == offsets.length - 1) ? getOriginalSize() - 1 : offsets[i + 1];
        int len = (endOffset - begOffset - 1);
        return extract(begOffset, len);
    }

    public Integer[] recordSearchOffsets(byte[] query) {
        Set<Integer> results = new HashSet<Integer>();
        Range range = getRange(query);

        long sp = range.first, ep = range.second;
        if (ep - sp + 1 <= 0) {
            return new Integer[0];
        }

        for (long i = 0; i < ep - sp + 1; i++) {
            results.add(offsets[searchOffset((int) lookupSA(sp + i))]);
        }

        return results.toArray(new Integer[results.size()]);
    }

    public long recordCount(byte[] query) {
        return recordSearchOffsets(query).length;
    }

    public byte[][] recordSearch(byte[] query) {
        Set<Integer> recordIds = new HashSet<Integer>();
        ArrayList<byte[]> results = new ArrayList<byte[]>();
        Range range = getRange(query);

        long sp = range.first, ep = range.second;
        if (ep - sp + 1 <= 0) {
            return new byte[0][0];
        }

        for (long i = 0; i < ep - sp + 1; i++) {
            long saVal = lookupSA(sp + i);
            int recordId = searchOffset((int) saVal);
            if(!recordIds.contains(recordId)) {
                results.add(getRecord(recordId));
                recordIds.add(recordId);
            }
        }

        return results.toArray(new byte[results.size()][]);
    }

    public byte[][] recordRangeSearch(byte[] queryBegin, byte[] queryEnd) {
        Set<Integer> recordIds = new HashSet<Integer>();
        ArrayList<byte[]> results = new ArrayList<byte[]>();
        Range rangeBegin = getRange(queryBegin);
        Range rangeEnd = getRange(queryEnd);

        long sp = rangeBegin.first, ep = rangeEnd.second;
        if (ep - sp + 1 <= 0) {
            return new byte[0][0];
        }

        for (long i = 0; i < ep - sp + 1; i++) {
            long saVal = lookupSA(sp + i);
            int recordId = searchOffset((int) saVal);
            if(!recordIds.contains(recordId)) {
                results.add(getRecord(recordId));
                recordIds.add(recordId);
            }
        }

        return results.toArray(new byte[results.size()][]);
    }

    public byte[][] recordSearchRegex(String query) throws RegExParsingException {
        Map<Long, Integer> regexOffsetResults = regexSearch(query);
        Set<Integer> recordIds = new HashSet<Integer>();
        ArrayList<byte[]> results = new ArrayList<byte[]>();
        for(Long offset: regexOffsetResults.keySet()) {
            int recordId = searchOffset(offset.intValue());
            if(!recordIds.contains(recordId)) {
                results.add(getRecord(recordId));
                recordIds.add(recordId);
            }
        }
        return results.toArray(new byte[results.size()][]);
    }

    public byte[][] multiSearch(QueryType[] queryTypes, byte[][][] queries) {
        assert(queryTypes.length == queries.length);
        Set<Integer> recordIds = new HashSet<Integer>();
        ArrayList<byte[]> results = new ArrayList<byte[]>();

        if(queries.length == 0) {
            throw new IllegalArgumentException("multiSearch called with empty queries");
        }

        // Get all ranges
        ArrayList<Range> ranges = new ArrayList<Range>();
        for (int qid = 0; qid < queries.length; qid++) {
            Range range;

            switch (queryTypes[qid]) {
                case Search:
                {
                    range = getRange(queries[qid][0]);
                    break;
                }
                case RangeSearch:
                {
                    byte[] queryBegin = queries[qid][0];
                    byte[] queryEnd = queries[qid][1];
                    Range rangeBegin, rangeEnd;
                    rangeBegin = getRange(queryBegin);
                    rangeEnd = getRange(queryEnd);
                    range = new Range(rangeBegin.first, rangeEnd.second);
                    break;
                }
                default:
                {
                    throw new UnsupportedOperationException("Unsupported QueryType");
                }
            }

            if (range.second - range.first + 1 > 0) {
                ranges.add(range);
            } else {
                return new byte[0][0];
            }
        }
        int numRanges = ranges.size();

        Collections.sort(ranges, new RangeSizeComparator());

        // Populate the set of offsets corresponding to the first range
        Range firstRange = ranges.get(0);
        Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
        {
            long sp = firstRange.first, ep = firstRange.second;
            for (long i = 0; i < ep - sp + 1; i++) {
                long saVal = lookupSA(sp + i);
                int recordId = searchOffset((int) saVal);
                recordIds.add(recordId);
                counts.put(recordId, 1);
            }
        }

        ranges.remove(firstRange);
        for (Range range: ranges) {
            long sp = range.first, ep = range.second;

            for (long i = 0; i < ep - sp + 1; i++) {
                long saVal = lookupSA(sp + i);
                int recordId = searchOffset((int) saVal);
                if (recordIds.contains(recordId)) {
                    counts.put(recordId, counts.get(recordId) + 1);
                }
            }
        }

        for (int recordId: recordIds) {
            if (counts.get(recordId) == numRanges) {
                results.add(getRecord(recordId));
            }
        }

        return results.toArray(new byte[results.size()][]);
    }

    public byte[][] extractRecords(int offset, int length) {
        byte[][] records = new byte[offsets.length][];
        for(int i = 0; i < records.length; i++) {
            int curOffset = offsets[i] + offset;
            int nextOffset = (i == records.length - 1) ? getOriginalSize() : offsets[i + 1];
            length = (length < nextOffset - curOffset - 1) ? length : nextOffset - curOffset - 1;
            records[i] = extract(curOffset, length);
        }
        return records;
    }
}
