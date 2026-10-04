package org.fuzz.util;

import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggingEvent;

   
                             
   
public class ColorPrint extends PatternLayout {

                
    private static final String RESET = "\u001b[0m";
    private static final String RED = "\u001b[31m";             
    private static final String YELLOW = "\u001b[33m";         
    private static final String GREEN = "\u001b[32m";          
    private static final String BLUE = "\u001b[34m";            
    private static final String CYAN = "\u001b[36m";            


    @Override
    public String format(LoggingEvent event) {
        String msg = super.format(event);
        Level  lvl = event.getLevel();

        switch (lvl.toInt()) {
            case Level.FATAL_INT:
            case Level.ERROR_INT: return RED   + msg + RESET;
            case Level.WARN_INT:  return YELLOW+ msg + RESET;
        }

        String color = lvl == Level.INFO  ? GREEN :
                lvl == Level.DEBUG ? BLUE  :
                        lvl == Level.TRACE ? CYAN  : RESET;

        return msg.replaceFirst(lvl.toString(), color + lvl + RESET);
    }
}