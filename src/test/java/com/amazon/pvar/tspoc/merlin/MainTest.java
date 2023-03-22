package com.amazon.pvar.tspoc.merlin;

import com.amazon.pvar.tspoc.merlin.experiments.Main;
import com.amazon.pvar.tspoc.merlin.experiments.Main.MerlinResult;
import com.google.gson.*;

import java.io.FileReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class MainTest extends AbstractCallGraphTest {

  private String[] buildArgs(Path testFile, Path outputFile) {
    /*
     *  -d,--directory <dir>        The directory containing the .js files to be
     *                              analyzed. One of -d or -f must be specified.
     *  -f,--file <file>            The .js file to be analyzed. One of -d or -f
     *                              must be specified.
     *  -fg,--dump-flowgraph        Output the TAJS flowgraph representation of
     *                              the program
     *  -h                          print this help message
     *  -o,--output <output-file>   The location to write Merlin's results
     * 
     */
    String[] args = { "-f", testFile.toAbsolutePath().toString(), "-o", outputFile.toAbsolutePath().toString() };

    return args;
  }

  @Test
  public void runOnFoxxBenchmark() throws java.io.FileNotFoundException {

      // Arrange
      Path foxxModule = Path.of("scripts", "evaluation", "eval-targets", "node_modules", "foxx-framework"); 
      Path inputFile =  Path.of(foxxModule.toString(), "bin", "foxxy");
      Path outputFile = Path.of("output-runs");

      if (outputFile.toFile().exists()) {
        outputFile.toFile().delete();
      }

      // You need to have `npm install` in the scripts/evaluation/eval-targets directory and (if the postinstall script doesn't work)
      // in scripts/evaluation/eval-targets/foxx-framework directory as well.
      assert Path.of(foxxModule.toString(), "node_modules").toFile().exists(); 

      // Act
      Main.main(buildArgs(inputFile, outputFile));

      // Assert
      assert outputFile.toFile().exists();

      // assert that we can parse the output file as JSON
      MerlinResult result = (new Gson()).fromJson(new FileReader(outputFile.toFile()), MerlinResult.class);

      // Clean up
      outputFile.toFile().delete();
  }

  
}
