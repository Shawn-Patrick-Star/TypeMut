package org.difftest;

import lombok.Getter;
import lombok.Setter;
import org.difftest.model.DTResult;
import org.difftest.model.artifact.ExecutionArtifact;

import java.nio.file.Path;
import java.util.List;

   
                            
                                        
                                                
   
@Getter
@Setter
public class DTContext {
           
    private Path sourceDir;
    private String mainClassName;

                            
    private List<ExecutionArtifact> compilerArtifacts;


             
    private DTResult finalResult;
}
