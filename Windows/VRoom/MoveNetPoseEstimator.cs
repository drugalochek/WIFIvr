using System.Drawing;
using System.Net.Http;
using System.Security.Cryptography;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;
using OpenCvSharp;

namespace VRoom;

internal sealed class MoveNetPoseEstimator : IDisposable
{
    private const int InputSize = 192;
    private const string ModelUrl = "https://huggingface.co/Xenova/movenet-singlepose-lightning/resolve/main/onnx/model.onnx";
    private const string ModelSha256 = "1ad4f8d6c2f776a9967db3993c9ca740bc350104f9d37c151dc183fc29a464ad";
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(3) };

    private readonly InferenceSession _session;
    private readonly string _inputName;
    private readonly string _outputName;

    private MoveNetPoseEstimator(InferenceSession session)
    {
        _session = session;
        _inputName = session.InputMetadata.Keys.Single();
        _outputName = session.OutputMetadata.Keys.Single();
    }

    public static async Task<MoveNetPoseEstimator> CreateAsync(CancellationToken cancellationToken)
    {
        var modelDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "VRoom", "models");
        Directory.CreateDirectory(modelDirectory);
        var modelPath = Path.Combine(modelDirectory, "movenet-singlepose-lightning.onnx");

        if (!await IsValidModelAsync(modelPath, cancellationToken).ConfigureAwait(false))
        {
            var temporaryPath = modelPath + ".download";
            try
            {
                using var response = await Http.GetAsync(ModelUrl, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                    .ConfigureAwait(false);
                response.EnsureSuccessStatusCode();
                await using (var output = new FileStream(temporaryPath, FileMode.Create, FileAccess.Write, FileShare.None))
                await using (var downloadStream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false))
                    await downloadStream.CopyToAsync(output, cancellationToken).ConfigureAwait(false);

                if (!await IsValidModelAsync(temporaryPath, cancellationToken).ConfigureAwait(false))
                    throw new InvalidDataException("SHA-256 модели MoveNet не совпадает с опубликованной контрольной суммой.");

                File.Move(temporaryPath, modelPath, true);
            }
            finally
            {
                if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
            }
        }

        cancellationToken.ThrowIfCancellationRequested();
        using var options = new SessionOptions
        {
            GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL,
            IntraOpNumThreads = Math.Clamp(Environment.ProcessorCount / 2, 1, 4),
            ExecutionMode = ExecutionMode.ORT_SEQUENTIAL
        };
        var session = new InferenceSession(modelPath, options);
        var input = session.InputMetadata.Values.Single();
        if (input.ElementType != typeof(int))
        {
            session.Dispose();
            throw new InvalidDataException($"Неожиданный тип входа MoveNet: {input.ElementType}. Ожидался int32.");
        }
        return new MoveNetPoseEstimator(session);
    }

    private static async Task<bool> IsValidModelAsync(string path, CancellationToken cancellationToken)
    {
        if (!File.Exists(path)) return false;
        var bytes = await File.ReadAllBytesAsync(path, cancellationToken).ConfigureAwait(false);
        if (bytes.Length < 1_000_000) return false;
        return Convert.ToHexString(SHA256.HashData(bytes)).Equals(ModelSha256, StringComparison.OrdinalIgnoreCase);
    }

    public PoseFrame Estimate(Mat bgrFrame)
    {
        if (bgrFrame.Empty()) return PoseFrame.Empty;

        var scale = Math.Min((double)InputSize / bgrFrame.Width, (double)InputSize / bgrFrame.Height);
        var resizedWidth = Math.Max(1, (int)Math.Round(bgrFrame.Width * scale));
        var resizedHeight = Math.Max(1, (int)Math.Round(bgrFrame.Height * scale));
        var padLeft = (InputSize - resizedWidth) / 2;
        var padTop = (InputSize - resizedHeight) / 2;

        using var resized = new Mat();
        using var squareBgr = new Mat(new OpenCvSharp.Size(InputSize, InputSize), MatType.CV_8UC3, Scalar.All(0));
        using var roi = new Mat(squareBgr, new Rect(padLeft, padTop, resizedWidth, resizedHeight));
        using var rgb = new Mat();

        Cv2.Resize(bgrFrame, resized, new OpenCvSharp.Size(resizedWidth, resizedHeight));
        resized.CopyTo(roi);
        Cv2.CvtColor(squareBgr, rgb, ColorConversionCodes.BGR2RGB);

        var tensor = new DenseTensor<int>(new[] { 1, InputSize, InputSize, 3 });
        for (var y = 0; y < InputSize; y++)
        for (var x = 0; x < InputSize; x++)
        {
            var pixel = rgb.At<Vec3b>(y, x);
            tensor[0, y, x, 0] = pixel.Item0;
            tensor[0, y, x, 1] = pixel.Item1;
            tensor[0, y, x, 2] = pixel.Item2;
        }

        using var results = _session.Run(new[] { NamedOnnxValue.CreateFromTensor(_inputName, tensor) });
        var output = results.Single(x => x.Name == _outputName).AsTensor<float>();
        if (output.Length < 51)
            throw new InvalidDataException("MoveNet вернул неполный набор ключевых точек.");

        var points = new PointF[17];
        var scores = new float[17];
        var scoreTotal = 0f;
        for (var i = 0; i < 17; i++)
        {
            var y = output[0, 0, i, 0];
            var x = output[0, 0, i, 1];
            var score = Math.Clamp(output[0, 0, i, 2], 0f, 1f);
            var imageX = (x * InputSize - padLeft) / scale / bgrFrame.Width;
            var imageY = (y * InputSize - padTop) / scale / bgrFrame.Height;
            points[i] = new PointF((float)Math.Clamp(imageX, 0.0, 1.0), (float)Math.Clamp(imageY, 0.0, 1.0));
            scores[i] = score;
            scoreTotal += score;
        }

        return new PoseFrame(points, scores, scoreTotal / 17f);
    }

    public void Dispose() => _session.Dispose();
}

internal sealed record PoseFrame(PointF[] Points, float[] Confidence, float AverageConfidence)
{
    public static PoseFrame Empty { get; } = new(Array.Empty<PointF>(), Array.Empty<float>(), 0f);
    public bool IsDetected => Points.Length == 17 && AverageConfidence >= 0.20f;
}
