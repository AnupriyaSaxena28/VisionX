"""
enrollment/enroll.py — Production Enrollment Pipeline
=====================================================
Takes a folder of labeled face images for one person:
  → Runs BlazeFace crop
  → MobileFaceNet embedding (ONNX or TFLite)
  → Outputs a JSON file with {name, embedding[512], enrolled_at}

Usage:
  python enroll.py --folder ./images/Virat_Kohli --name "Virat Kohli"
  python enroll.py --folder ./images/Virat_Kohli --name "Virat Kohli" --model onnx
"""

import os
import glob
import json
import argparse
import datetime
import cv2
import numpy as np
from pathlib import Path

# Auto-detect runtime environment
def get_base_dir():
    """Works both locally and in Colab."""
    p = Path(__file__).resolve().parent.parent
    if p.exists():
        return p
    return Path('/content/ml-pipeline')

BASE_DIR = get_base_dir()
MODELS_DIR = BASE_DIR / "models"

# Model paths (in order of preference)
ONNX_MODEL_PATHS = [
    Path('/content/insightface_models/models/buffalo_s/w600k_mbf.onnx'),
    MODELS_DIR / 'w600k_mbf.onnx',
]
TFLITE_MODEL_PATHS = [
    Path('/content/mobilefacenet_production.tflite'),
    MODELS_DIR / 'mobilefacenet_production.tflite',
    MODELS_DIR / 'mobilefacenet_int8.tflite',
]

def find_model(paths):
    for p in paths:
        if p.exists():
            return str(p)
    return None


# ── Face Detection: MediaPipe BlazeFace ──────────────────────────────────

def crop_face_mediapipe(image):
    """Detect and crop face using MediaPipe BlazeFace."""
    try:
        import mediapipe as mp
        mp_face = mp.solutions.face_detection
        
        with mp_face.FaceDetection(model_selection=0, min_detection_confidence=0.5) as detector:
            results = detector.process(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
            if not results.detections:
                return None
            
            det = max(results.detections, key=lambda d: d.score[0])
            bbox = det.location_data.relative_bounding_box
            ih, iw = image.shape[:2]
            
            # Add 15% margin for better recognition
            margin = 0.15
            x = max(0, int((bbox.xmin - margin * bbox.width) * iw))
            y = max(0, int((bbox.ymin - margin * bbox.height) * ih))
            w = min(iw, int(bbox.width * (1 + 2 * margin) * iw))
            h = min(ih, int(bbox.height * (1 + 2 * margin) * ih))
            
            return image[y:y+h, x:x+w]
    except ImportError:
        return crop_face_opencv(image)

def crop_face_opencv(image):
    """Fallback: OpenCV Haar cascade face detection."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    cascade = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')
    faces = cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(30, 30))
    
    if len(faces) == 0:
        return None
    
    # Pick largest face
    x, y, w, h = max(faces, key=lambda f: f[2] * f[3])
    margin = int(0.15 * max(w, h))
    x1 = max(0, x - margin)
    y1 = max(0, y - margin)
    x2 = min(image.shape[1], x + w + margin)
    y2 = min(image.shape[0], y + h + margin)
    
    return image[y1:y2, x1:x2]


# ── Embedding Extraction ────────────────────────────────────────────────

def extract_embedding_onnx(onnx_path, face_img):
    """Extract 512-d embedding using ONNX Runtime."""
    import onnxruntime as ort
    
    session = ort.InferenceSession(onnx_path, providers=['CPUExecutionProvider'])
    input_name = session.get_inputs()[0].name
    input_shape = session.get_inputs()[0].shape  # (1, 3, 112, 112) NCHW
    
    # Preprocess: resize, normalize, transpose to NCHW
    face = cv2.resize(face_img, (112, 112))
    face = cv2.cvtColor(face, cv2.COLOR_BGR2RGB)
    face = (face.astype(np.float32) - 127.5) / 127.5
    face = np.transpose(face, (2, 0, 1))  # HWC → CHW
    face = np.expand_dims(face, axis=0)    # Add batch dim
    
    result = session.run(None, {input_name: face})
    embedding = result[0][0]
    return embedding / np.linalg.norm(embedding)

def extract_embedding_tflite(tflite_path, face_img):
    """Extract embedding using TFLite interpreter."""
    import tensorflow as tf
    
    interpreter = tf.lite.Interpreter(model_path=tflite_path)
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    out = interpreter.get_output_details()[0]
    
    # Get expected input shape
    h, w = inp['shape'][1], inp['shape'][2]
    
    face = cv2.resize(face_img, (w, h))
    face = cv2.cvtColor(face, cv2.COLOR_BGR2RGB)
    face = (face.astype(np.float32) - 127.5) / 127.5
    face = np.expand_dims(face, axis=0)  # NHWC
    
    if inp['dtype'] == np.int8:
        scale, zp = inp['quantization']
        face = (face / scale + zp).astype(np.int8)
    
    interpreter.set_tensor(inp['index'], face)
    interpreter.invoke()
    
    embedding = interpreter.get_tensor(out['index'])[0]
    if out['dtype'] == np.int8:
        scale, zp = out['quantization']
        embedding = (embedding.astype(np.float32) - zp) * scale
    
    return embedding / np.linalg.norm(embedding)


# ── Main Enrollment Logic ────────────────────────────────────────────────

def enroll(folder_path, name, backend='auto'):
    """
    Enroll a person from a folder of face images.
    
    Args:
        folder_path: Path to folder containing face images
        name: Name of the person
        backend: 'onnx', 'tflite', or 'auto'
    """
    print(f"═══════════════════════════════════════")
    print(f"  Enrolling: {name}")
    print(f"  Source:    {folder_path}")
    print(f"═══════════════════════════════════════")
    
    # Select model backend
    extract_fn = None
    model_path = None
    
    if backend in ('auto', 'onnx'):
        model_path = find_model(ONNX_MODEL_PATHS)
        if model_path:
            extract_fn = lambda img: extract_embedding_onnx(model_path, img)
            print(f"  Backend:   ONNX Runtime")
            print(f"  Model:     {model_path}")
    
    if extract_fn is None and backend in ('auto', 'tflite'):
        model_path = find_model(TFLITE_MODEL_PATHS)
        if model_path:
            extract_fn = lambda img: extract_embedding_tflite(model_path, img)
            print(f"  Backend:   TFLite")
            print(f"  Model:     {model_path}")
    
    if extract_fn is None:
        print("❌ No model found! Please ensure the model file exists.")
        return None
    
    # Find all images
    extensions = ('*.jpg', '*.jpeg', '*.png', '*.bmp', '*.webp')
    image_paths = []
    for ext in extensions:
        image_paths.extend(glob.glob(os.path.join(folder_path, ext)))
        image_paths.extend(glob.glob(os.path.join(folder_path, ext.upper())))
    
    if not image_paths:
        print(f"❌ No images found in {folder_path}")
        return None
    
    print(f"\n  Found {len(image_paths)} images. Processing...")
    
    embeddings = []
    for img_path in image_paths:
        img = cv2.imread(img_path)
        if img is None:
            print(f"  ⚠️  Could not read: {os.path.basename(img_path)}")
            continue
        
        # Detect and crop face
        face = crop_face_mediapipe(img)
        if face is None:
            print(f"  ⚠️  No face found in: {os.path.basename(img_path)}")
            continue
        
        # Extract embedding
        emb = extract_fn(face)
        embeddings.append(emb)
        print(f"  ✅ {os.path.basename(img_path)} → {len(emb)}-d embedding")
    
    if not embeddings:
        print("❌ Could not extract any embeddings.")
        return None
    
    # Average embeddings for robustness
    final_embedding = np.mean(embeddings, axis=0)
    final_embedding = final_embedding / np.linalg.norm(final_embedding)
    
    # Build output JSON
    output_data = {
        "name": name,
        "embedding": final_embedding.tolist(),
        "enrolled_at": datetime.datetime.utcnow().isoformat() + "Z"
    }
    
    # Save
    safe_name = name.replace(' ', '_').replace('/', '_')
    out_file = os.path.join(os.path.dirname(folder_path), f"{safe_name}_enrollment.json")
    with open(out_file, 'w') as f:
        json.dump(output_data, f, indent=2)
    
    print(f"\n  ✅ Enrollment successful!")
    print(f"  📁 Saved: {out_file}")
    print(f"  📐 Embedding: {len(final_embedding)}-d (L2-normalized)")
    print(f"  📸 Images used: {len(embeddings)}/{len(image_paths)}")
    
    return output_data


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Enroll a user for face recognition.")
    parser.add_argument("--folder", type=str, required=True, help="Path to folder with face images")
    parser.add_argument("--name", type=str, required=True, help="Name of the person")
    parser.add_argument("--model", type=str, default="auto", choices=['auto', 'onnx', 'tflite'],
                        help="Backend: 'onnx', 'tflite', or 'auto' (default)")
    args = parser.parse_args()
    
    enroll(args.folder, args.name, args.model)
