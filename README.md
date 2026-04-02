# Gemini AI Speech-to-Text (STT)

A high-precision speech-to-text application leveraging the thinking capabilities (Thinking Process) of the Google Gemini 3.0 Flash Preview model.

## 🚀 Key Features

- **Hybrid Audio Input**: Supports both real-time recording (MP3/WAV) directly in the browser and audio file uploads (drag & drop).
- **Visualization of Thinking Process**: Stream the thinking process of Gemini 3.0 Flash Preview to see how the AI makes its decisions.
- **AI-Powered Text Improvement**: Refine transcription results by providing instructions to summarize, translate, or change the tone.
- **Custom Vocabulary**: Register specialized terms or proper nouns with specific readings to prevent misspellings and improve accuracy.
- **Data Retention Management**: Automatically cleans up history and audio files based on user settings (5 to 1440 minutes).
- **Multiple Design Themes**: Supports theme switching, including Gaming, Retro, and Modern modes.

## 🛠 Tech Stack

- **Backend**: Python 3.11, Flask, SQLAlchemy (ORM), MariaDB, Gunicorn
- **Frontend**: HTML5, JavaScript (Vanilla ES6+), Bootstrap 5.3
- **API Communication**: Gemini REST API (Streaming via Server-Sent Events)
- **Infrastructure**: Apache 2.4 (Reverse Proxy), Systemd, SSL (Let's Encrypt)

## 📦 Setup and Execution

### Prerequisites
- Python 3.11 or higher
- MariaDB

### Steps
1. **Clone the Repository**
2. **Install Dependencies**
   ```bash
   cd app
   python -m venv venv
   source venv/bin/activate
   pip install -r requirements.txt
   ```
3. **Configuration**
   Create an `app/.env` file and set the following items:
   - `SECRET_KEY`: Flask secret key
   - `SQLALCHEMY_DATABASE_URI`: MariaDB connection URL
   - `ENCRYPTION_KEY`: Key for encrypting API keys
4. **Run**
   ```bash
   python app.py
   ```
   The server will start at `http://localhost:8003`.

## 📂 Directory Structure
- `app/`: Application source code
  - `app.py`: Main logic
  - `templates/`: HTML templates
  - `static/`: CSS, JS, and image files
  - `uploads/`: Temporary audio file storage
- `引き継ぎ資料.txt`: Detailed development handover notes (private)
