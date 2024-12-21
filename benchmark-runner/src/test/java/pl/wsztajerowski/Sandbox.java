package pl.wsztajerowski;

import com.fatboyindustrial.gsonjavatime.Converters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.google.common.reflect.TypeToken;
import pl.wsztajerowski.entities.jmh.BenchmarkMetadata;
import pl.wsztajerowski.entities.jmh.JmhBenchmark;
import pl.wsztajerowski.entities.jmh.JmhBenchmarkId;
import pl.wsztajerowski.entities.jmh.JmhResult;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.nio.file.Files.list;

@Disabled
class Sandbox {

    @Test
    void run_jmh_benchmark() throws IOException, InterruptedException {
//        java -jar jmh-benchmarks.jar  -f 1 -wi 1 -i 1 -rf json
        List<String> commands = new ArrayList<>();
        commands.add("java"); // command
        commands.add("-jar"); // command
        commands.add("../jmh-benchmarks/target/jmh-benchmarks.jar"); // command
        commands.add("-f"); // command
        commands.add("1"); // command
        commands.add("-i"); // command
        commands.add("5"); // command
        commands.add("-wi"); // command
        commands.add("5"); // command
        commands.add("-rf"); // command
        commands.add("json"); // command

        // creating the process
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.inheritIO();
        // starting the process
        Process process = pb.start();
        process.waitFor();
    }

    @Test
    void run_jmh_benchmark_with_async_profiler() throws IOException, InterruptedException {
//        java -jar jmh-benchmarks.jar  -f 1 -wi 1 -i 1 -rf json
        List<String> commands = new ArrayList<>();
        commands.add("java"); // command
        commands.add("-jar"); // command
        commands.add("../jmh-benchmarks/target/jmh-benchmarks.jar"); // command
        commands.add("-f"); // command
        commands.add("1"); // command
        commands.add("-i"); // command
        commands.add("1"); // command
        commands.add("-wi"); // command
        commands.add("1"); // command
        commands.add("-rf"); // command
        commands.add("json"); // command
        commands.add("-prof"); // command
        commands.add("async:libPath=/Users/Wiktor/workspace/way-to-neo/async-profiler/build/libasyncProfiler.so;output=flamegraph;interval=999;dir=flame"); // command

        // creating the process
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.inheritIO();
        pb.redirectErrorStream(true);
        // starting the process
        Process process = pb.start();
        process.waitFor();
    }

    @Test
    void capture_process_output() throws IOException, InterruptedException {
        List<String> commands = new ArrayList<>();
        commands.add("echo");
        commands.add("'Hello world!'");

        // creating the process
        ProcessBuilder pb = new ProcessBuilder(commands);

        // starting the process
        Process process = pb.start();
        Files.copy(
            process.getErrorStream(),
            Paths.get("error.txt"),
            StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
            process.getInputStream(),
            Paths.get("output.txt"),
            StandardCopyOption.REPLACE_EXISTING);

        process.waitFor();
    }

    @Test
    void save_flamegraphs_on_S3() {
        ProfileCredentialsProvider credentialsProvider = ProfileCredentialsProvider.create();
        Region region = Region.EU_NORTH_1;
        try (S3Client s3 = S3Client.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build()) {

            String bucketName = "java-wonderland";
            String objectKey = "gha-outputs/async/graph.html";
            Path flamegraph = Paths.get("flame", "pl.wsztajerowski.Incrementing_DekkersLock.g1-Throughput", "flame-cpu-forward.html");
            PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
//                .metadata(metadata)
                .build();

            s3.putObject(putOb, RequestBody.fromFile(flamegraph));
        }
    }

    @Test
    void json_to_object_mapping_using_gson() throws IOException {
        List<JmhResult> benchmarks = loadBenchamrkResultAndAddFlamegraphPaths();
        System.out.println(benchmarks);
    }

    @NotNull
    private static List<JmhResult> loadBenchamrkResultAndAddFlamegraphPaths() throws IOException {
        Gson gson = Converters.registerLocalDateTime(new GsonBuilder()
//            .registerTypeAdapter(Id.class, new IdTypeAdapter())
                .enableComplexMapKeySerialization()
                .serializeNulls()
//            .setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE)
                .setPrettyPrinting()
                .setVersion(1.0))
            .create();

        BufferedReader bufferedReader = new BufferedReader(
            new FileReader("jmh-result.json"));
        Type listType = new TypeToken<List<JmhResult>>() {
        }.getType();
        List<JmhResult> benchmarks = gson.fromJson(bufferedReader, listType);

//        Type listOfBenchmarksType = Types.newParameterizedType(List.class, Benchmark.class);
//        JsonAdapter<List<Benchmark>> jsonAdapter = moshi.adapter(listOfBenchmarksType);
//
//
//        Source fileSource = source(path);
//        List<Benchmark> benchmarks = jsonAdapter.fromJson(of(buffer(fileSource)));

        return benchmarks;
    }

    @Test
    void save_morphia_entity_to_mongo() throws IOException {
        String connectionString = System.getenv("MONGO_CONNECTION_STRING");
        MongoClientSettings settings = MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(connectionString))
            .build();
        // Create a new client and connect to the server
        try (MongoClient mongoClient = MongoClients.create(settings)) {
            final Datastore datastore = Morphia.createDatastore(mongoClient, "benchmarks");

            // tell morphia where to find your classes
            // can be called multiple times with different packages or classes
            datastore.getMapper().mapPackage("pl.wsztajerowski.entites");

//            List<Benchmark> benchmarks = mapJsonFileToJavaList();
            List<JmhResult> benchmarks = loadBenchamrkResultAndAddFlamegraphPaths();
            int counter = 7;
            String commitSha = "129345abceff";
            for (JmhResult jmhResult : benchmarks) {
                String dirSuffix = switch (jmhResult.mode()) {
                    case "thrpt":
                        yield "-Throughput";
                    default:
                        throw new IllegalArgumentException("Unknown benchmark mode: " + jmhResult.mode());
                };
                JmhBenchmarkId benchmarkId = new JmhBenchmarkId(
                    commitSha, jmhResult.benchmark(), jmhResult.mode());
//                String flamegraphsDir = jmhResult.benchmark + dirSuffix;
//                String s3Preffix = format("gha-outputs/commit-{0}/attempt-{1}/", benchmarkId.commitSha(), benchmarkId.runAttempt());
                BenchmarkMetadata benchmarkMetadata = new BenchmarkMetadata(Collections.emptyMap(),LocalDateTime.now(), Collections.emptyMap());
//                list(Path.of(flamegraphsDir))
//                    .forEach(path -> {
//                        String s3Key = s3Preffix + path.toString();
//                        saveFlamegraphOnS3(s3Key, path);
//                        String flamegraphName = getFilenameWithoutExtension(path);
//                        benchmarkMetadata.addFlamegraphPath(flamegraphName, s3Key);
//                    });

                JmhBenchmark jmhBenchmark = new JmhBenchmark(benchmarkId, jmhResult, benchmarkMetadata);
                datastore
                    .insert(jmhBenchmark);
//                    .find(JmhBenchmark.class)
//                    .filter(eq("benchmarkId", benchmarkId))
//                    .update(new UpdateOptions()
//                        .upsert(true), UpdateOperators.set("benchmarkMetadata", benchmarkMetadata));
//                System.out.println("Update results: " + update);
            }

        }
    }

    private void saveFlamegraphOnS3(String objectKey, Path pathToFile) {
        ProfileCredentialsProvider credentialsProvider = ProfileCredentialsProvider.create();
        Region region = Region.EU_NORTH_1;
        try (S3Client s3 = S3Client.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build()) {

            String bucketName = "java-wonderland";
            PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
//                .metadata(metadata)
                .build();

            s3.putObject(putOb, RequestBody.fromFile(pathToFile));
        }
    }

    @Test
    void scrapHtml() throws IOException {
        list(Path.of("target", "jcstress-results"))
            .filter(path -> path.endsWith("index.html"))
            .forEach(path -> {
                try {
                    scrapJcstressResult(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
    }

    private void scrapJcstressResult(Path path) throws IOException {
        System.out.println(path);
        Document doc = Jsoup.parse(path.toFile(), "UTF-8");
        Element endResult = doc.getElementsByClass("endResult")
            .first();
        endResult
            .siblingElements()
            .forEach(element -> System.out.println("endResult data: " + element.text()));
        System.out.println("endResult data: " + endResult.text());
        String text = endResult
            .nextElementSibling()
            .text();
//        String overallRate = doc.getElementsMatchingText("Overall pass rate")
        List<String> hrefs = doc.select("h3:containsOwn(INTERESTING) ~ table")
            .first()
            .select("td > a")
            .stream()
//            .map(a -> a.attr("href"))
            .map(Element::text)
            .toList();
        hrefs
            .forEach(a -> System.out.println(a));
        String overallRate = doc.select("h3:containsOwn(INTERESTING) ~ table")
            .first()
//            .select("td > a")
//            .forEach( a -> System.out.println(a))
//            .parent()
//            .nextElementSibling()
//            .firstElementSibling()
//            .firstElementChild()
            .text();
        System.out.println("Overall rate: " + overallRate);
        String specName = doc
            .select("td:containsOwn(java.specification.name)")
//            .getElementsContainingText("java.specification.name")

            .first()
            .nextElementSibling()
//            .firstElementSibling()
//            .text()
            .toString();
        System.out.println("Spec name: " + specName);
    }
}
