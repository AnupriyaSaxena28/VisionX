import Foundation
import UIKit

// MARK: - Placeholder imports for inference engine classes
// These will be replaced with actual imports once the inference engine is integrated.
// import CoreML
// import TensorFlowLite
// import FaceDetector
// import FaceEmbedding

// MARK: - Liveness Challenge State Machine

/// Manages the blink + head-turn liveness challenge as a simple state machine.
final class LivenessChallengeManager {

    enum Step: String {
        case idle          = "idle"
        case blinkLeft     = "blink_left"
        case blinkRight    = "blink_right"
        case turnLeft      = "turn_left"
        case turnRight     = "turn_right"
        case completed     = "completed"
        case failed        = "failed"
    }

    private(set) var currentStep: Step = .idle
    private(set) var progress: Int = 0 // 0–100

    private let steps: [Step] = [.blinkLeft, .blinkRight, .turnLeft, .turnRight, .completed]
    private var stepIndex: Int = 0

    // MARK: - Public API

    /// Resets and starts the challenge sequence.
    func start() {
        stepIndex = 0
        currentStep = steps[stepIndex]
        progress = 0
    }

    /// Advances to the next step in the challenge.
    /// Returns `true` when the full challenge is completed.
    @discardableResult
    func advanceToNextStep() -> Bool {
        guard currentStep != .completed && currentStep != .failed else {
            return currentStep == .completed
        }

        stepIndex += 1
        if stepIndex < steps.count {
            currentStep = steps[stepIndex]
            progress = Int((Double(stepIndex) / Double(steps.count - 1)) * 100)
        } else {
            currentStep = .completed
            progress = 100
        }
        return currentStep == .completed
    }

    /// Marks the current challenge as failed.
    func fail() {
        currentStep = .failed
        progress = 0
    }

    /// Returns a dictionary representation of the current state.
    func stateDictionary() -> [String: Any] {
        return [
            "step": currentStep.rawValue,
            "progress": progress
        ]
    }
}

// MARK: - FaceAuthModule

@objc(FaceAuthModule)
final class FaceAuthModule: NSObject {

    // MARK: - Properties

    private var isInitialized = false
    private var modelPath: String?
    private let livenessManager = LivenessChallengeManager()

    /// Cosine-similarity threshold for a positive match.
    private let matchThreshold: Float = 0.7

    /// Serial queue for thread-safe access to shared state.
    private let stateQueue = DispatchQueue(label: "com.datalakefaceauth.faceauth.state")

    // MARK: - Module Configuration

    @objc static func moduleName() -> String! {
        return "FaceAuthModule"
    }

    @objc static func requiresMainQueueSetup() -> Bool {
        return false
    }

    /// Override to declare that this module does not emit events.
    /// Add event names here if event emission is needed in the future.
    @objc func supportedEvents() -> [String] {
        return []
    }

    // MARK: - 1. initialize

    /// Loads CoreML / TFLite models asynchronously on a background queue.
    /// - Parameters:
    ///   - modelPath: Filesystem path to the model bundle or directory.
    ///   - resolve: Promise resolve callback.
    ///   - reject: Promise reject callback.
    @objc func initialize(
        _ modelPath: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else {
                reject("E_DEALLOCATED", "FaceAuthModule was deallocated", nil)
                return
            }

            do {
                // Validate model path exists
                guard FileManager.default.fileExists(atPath: modelPath) else {
                    reject(
                        "E_MODEL_NOT_FOUND",
                        "Model file not found at path: \(modelPath)",
                        nil
                    )
                    return
                }

                // TODO: Load CoreML model
                // let mlModel = try MLModel(contentsOf: URL(fileURLWithPath: modelPath))

                // TODO: Load TFLite interpreter
                // let interpreter = try Interpreter(modelPath: modelPath)
                // try interpreter.allocateTensors()

                self.stateQueue.sync {
                    self.modelPath = modelPath
                    self.isInitialized = true
                }

                NSLog("[FaceAuthModule] Models initialized from: %@", modelPath)
                resolve(nil)

            } catch {
                reject(
                    "E_INIT_FAILED",
                    "Failed to initialize models: \(error.localizedDescription)",
                    error
                )
            }
        }
    }

    // MARK: - 2. enrollFace

    /// Runs the enrollment pipeline on each provided image, averages the resulting
    /// 128-float embeddings, and stores the result in the local SQLite database.
    /// - Parameters:
    ///   - name: Display name for the enrolled face.
    ///   - imagePaths: Array of filesystem paths to enrollment images.
    ///   - resolve: Promise resolve callback — returns `{success, id}`.
    ///   - reject: Promise reject callback.
    @objc func enrollFace(
        _ name: String,
        imagePaths: [String],
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else {
                reject("E_DEALLOCATED", "FaceAuthModule was deallocated", nil)
                return
            }

            guard self.stateQueue.sync(execute: { self.isInitialized }) else {
                reject("E_NOT_INITIALIZED", "Module not initialized. Call initialize() first.", nil)
                return
            }

            guard !name.isEmpty else {
                reject("E_INVALID_NAME", "Name must not be empty.", nil)
                return
            }

            guard !imagePaths.isEmpty else {
                reject("E_NO_IMAGES", "At least one image path is required.", nil)
                return
            }

            do {
                var allEmbeddings: [[Float]] = []

                for path in imagePaths {
                    guard let image = UIImage(contentsOfFile: path) else {
                        reject(
                            "E_IMAGE_LOAD",
                            "Failed to load image at path: \(path)",
                            nil
                        )
                        return
                    }

                    // TODO: Detect face region in `image`
                    // let faceRect = FaceDetector.detect(image)

                    // TODO: Extract 128-d embedding from the detected face
                    // let embedding = FaceEmbeddingExtractor.extract(image, faceRect)

                    // Placeholder: generate a dummy 128-float embedding
                    let embedding = Self.placeholderEmbedding()
                    allEmbeddings.append(embedding)
                }

                // Average all embeddings element-wise
                let averagedEmbedding = Self.averageEmbeddings(allEmbeddings)

                // Store in SQLite via DatabaseManager
                let enrollmentId = UUID().uuidString
                try DatabaseManager.shared.saveEnrollment(
                    id: enrollmentId,
                    name: name,
                    embedding: averagedEmbedding
                )

                NSLog("[FaceAuthModule] Enrolled '%@' with id: %@", name, enrollmentId)

                resolve([
                    "success": true,
                    "id": enrollmentId
                ] as [String: Any])

            } catch {
                reject(
                    "E_ENROLL_FAILED",
                    "Enrollment failed: \(error.localizedDescription)",
                    error
                )
            }
        }
    }

    // MARK: - 3. authenticate

    /// Decodes a base64-encoded frame, detects a face, performs a liveness check,
    /// extracts the embedding, and compares it against enrolled embeddings.
    /// - Parameters:
    ///   - frameBase64: Base64-encoded image data.
    ///   - resolve: Promise resolve callback — returns `{matched, name, score, livenessPass}`.
    ///   - reject: Promise reject callback.
    @objc func authenticate(
        _ frameBase64: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else {
                reject("E_DEALLOCATED", "FaceAuthModule was deallocated", nil)
                return
            }

            guard self.stateQueue.sync(execute: { self.isInitialized }) else {
                reject("E_NOT_INITIALIZED", "Module not initialized. Call initialize() first.", nil)
                return
            }

            // Decode base64 → UIImage
            guard let imageData = Data(base64Encoded: frameBase64, options: .ignoreUnknownCharacters),
                  let image = UIImage(data: imageData) else {
                reject("E_DECODE_FAILED", "Failed to decode base64 image data.", nil)
                return
            }

            do {
                // TODO: Detect face in the decoded image
                // let faceRect = FaceDetector.detect(image)
                // guard let faceRect = faceRect else { ... }

                // TODO: Perform liveness check
                // let livenessPass = LivenessChecker.check(image, faceRect)
                let livenessPass = true // Placeholder

                // TODO: Extract embedding from the detected face
                // let embedding = FaceEmbeddingExtractor.extract(image, faceRect)
                let queryEmbedding = Self.placeholderEmbedding() // Placeholder

                // Fetch all enrolled embeddings from the database
                let enrollments = try DatabaseManager.shared.fetchAllEnrollments()

                var bestScore: Float = -1.0
                var bestName: String = ""

                for enrollment in enrollments {
                    let score = Self.cosineSimilarity(queryEmbedding, enrollment.embedding)
                    if score > bestScore {
                        bestScore = score
                        bestName = enrollment.name
                    }
                }

                let matched = bestScore >= self.matchThreshold && livenessPass

                NSLog(
                    "[FaceAuthModule] Auth result — matched: %@, name: %@, score: %.4f, liveness: %@",
                    matched ? "YES" : "NO",
                    bestName,
                    bestScore,
                    livenessPass ? "PASS" : "FAIL"
                )

                resolve([
                    "matched": matched,
                    "name": matched ? bestName : "",
                    "score": bestScore,
                    "livenessPass": livenessPass
                ] as [String: Any])

            } catch {
                reject(
                    "E_AUTH_FAILED",
                    "Authentication failed: \(error.localizedDescription)",
                    error
                )
            }
        }
    }

    // MARK: - 4. startLivenessChallenge

    /// Starts the blink + head-turn liveness challenge state machine.
    /// - Parameters:
    ///   - resolve: Promise resolve callback.
    ///   - reject: Promise reject callback.
    @objc func startLivenessChallenge(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        stateQueue.async { [weak self] in
            guard let self = self else {
                reject("E_DEALLOCATED", "FaceAuthModule was deallocated", nil)
                return
            }

            self.livenessManager.start()

            NSLog("[FaceAuthModule] Liveness challenge started.")
            resolve(self.livenessManager.stateDictionary())
        }
    }

    // MARK: - 5. getLivenessChallengeState

    /// Returns the current state of the liveness challenge.
    /// - Parameters:
    ///   - resolve: Promise resolve callback — returns `{step, progress}`.
    ///   - reject: Promise reject callback.
    @objc func getLivenessChallengeState(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        stateQueue.async { [weak self] in
            guard let self = self else {
                reject("E_DEALLOCATED", "FaceAuthModule was deallocated", nil)
                return
            }

            resolve(self.livenessManager.stateDictionary())
        }
    }

    // MARK: - Utility: Cosine Similarity

    /// Computes the cosine similarity between two vectors of equal length.
    /// Returns a value in [-1, 1]; higher means more similar.
    private static func cosineSimilarity(_ a: [Float], _ b: [Float]) -> Float {
        guard a.count == b.count, !a.isEmpty else { return 0.0 }

        var dotProduct: Float = 0.0
        var magnitudeA: Float = 0.0
        var magnitudeB: Float = 0.0

        for i in 0..<a.count {
            dotProduct += a[i] * b[i]
            magnitudeA += a[i] * a[i]
            magnitudeB += b[i] * b[i]
        }

        let denominator = sqrt(magnitudeA) * sqrt(magnitudeB)
        guard denominator > 0 else { return 0.0 }

        return dotProduct / denominator
    }

    // MARK: - Utility: Average Embeddings

    /// Averages an array of embeddings element-wise.
    private static func averageEmbeddings(_ embeddings: [[Float]]) -> [Float] {
        guard let first = embeddings.first else { return [] }
        let length = first.count
        var sum = [Float](repeating: 0.0, count: length)

        for embedding in embeddings {
            for i in 0..<length {
                sum[i] += embedding[i]
            }
        }

        let count = Float(embeddings.count)
        return sum.map { $0 / count }
    }

    // MARK: - Utility: Placeholder Embedding

    /// Generates a placeholder 128-dimensional embedding for development.
    /// Replace with real inference output.
    private static func placeholderEmbedding() -> [Float] {
        return (0..<128).map { _ in Float.random(in: -1.0...1.0) }
    }
}
