package pl.wsztajerowski.infra;

import com.fatboyindustrial.gsonjavatime.Converters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.entities.jmh.JmhResult;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.List;

public class ResultLoaderService {
    private final Gson gson;

    ResultLoaderService(Gson gson) {
        this.gson = gson;
    }

    public static ResultLoaderService getResultLoaderService() {
        Gson gson = Converters.registerLocalDateTime(new GsonBuilder()
                .enableComplexMapKeySerialization()
                .setVersion(1.0))
            .create();
        return new ResultLoaderService(gson);
    }

    public List<JmhResult> loadJmhResults(Path jmhResultFilePath) {
        try {
            Type listType = new TypeToken<List<JmhResult>>() {
            }.getType();
            return gson.fromJson(
                new BufferedReader(new FileReader(jmhResultFilePath.toFile())),
                listType);
        } catch (FileNotFoundException e) {
            throw new JavaWonderlandException(e);
        }
    }
}
