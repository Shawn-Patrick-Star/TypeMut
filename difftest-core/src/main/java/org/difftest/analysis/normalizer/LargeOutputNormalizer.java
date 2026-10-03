package org.difftest.analysis.normalizer;


   
                  
                                          
      
                                           
                                 
                                                            
   
public class LargeOutputNormalizer {

    private final int maxLines;
    private static final String TRUNCATION_KEYWORD = "[TRUNCATED]";
    private static final String LOGICAL_TRUNCATION_MARK = "\n<... TRUNCATED BY NORMALIZER ...>";

    public LargeOutputNormalizer(int maxLines) {
        this.maxLines = maxLines;
    }

    public String normalize(String content) {
        if (content == null) return "";

        String trimmed = content.trim();

                                            
        boolean isPhysicallyTruncated = trimmed.contains(TRUNCATION_KEYWORD);

                          
        int[] result = getPrefixLength(trimmed, maxLines);
        int prefixEndIndex = result[0];
        int linesFound = result[1];

                  
        if (linesFound >= maxLines || isPhysicallyTruncated) {
                                                      
            if (isPhysicallyTruncated && linesFound < maxLines) {
                       
                int lastNewLine = trimmed.lastIndexOf('\n', prefixEndIndex - 1);
                if (lastNewLine != -1) {
                    prefixEndIndex = lastNewLine;
                }
            }

                                        
            String prefix = trimmed.substring(0, prefixEndIndex).trim();

                                                      
            return prefix + LOGICAL_TRUNCATION_MARK;
        }

                            
        return trimmed;
    }

       
                            
       
    private int[] getPrefixLength(String content, int n) {
        if (content.isEmpty()) return new int[]{0, 0};

        int linesFound = 0;
        int pos = 0;
        int len = content.length();

        while (linesFound < n && pos < len) {
            int nextPos = content.indexOf('\n', pos);
            if (nextPos == -1) {
                return new int[]{len, linesFound + 1};
            }
            pos = nextPos + 1;
            linesFound++;
        }

        return new int[]{pos, linesFound};
    }
}
