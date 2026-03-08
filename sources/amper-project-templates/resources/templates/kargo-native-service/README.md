# 🎉 Kargo Native Service 🚀

Welcome to the **Kargo Native Service**! This project demonstrates how to build a high-performance, standalone microservice using [Kargo](https://github.com/kargo-build/kargo) and Kotlin/Native.

## 🌟 Awesome Features

- **Blazing Fast Ktor Server:** Runs as a native executable using the CIO engine! 🏎️💨
- **Ktor Client with Native Connector:** Uses the `kargo-build/ktor-client-native` Git dependency to make asynchronous HTTP requests natively. 🤯
- **Zero Config Git Dependencies:** Fetches the native Ktor engine *directly* from GitHub.
- **Standalone Linux Binary:** No JVM needed—perfect for Docker containers or serverless environments!
- **Traditional Maven Dependencies:** Mix native Git sources with standard packages like.

## ⚡ Setup & Build in Seconds

1. **Sync & Run with IntelliJ IDEA (Best Experience!)** 
   If you used the **Kargo Project Wizard**, your project is ready for the IDE! Just click the **Sync Kargo Project** button (or right-click `project.yaml` > **Sync**) to initialize dependencies. You can then run your service using the green gutter icons in `src/com/example/demo/main.kt`!

2. **Or build it with ONE command:**
   ```bash
   ./kargo build
   ```

   Boom! 💥 Your native service is ready at `dist/app`.

## 🚀 Run It!

1. **Start the service:**
   ```bash
   ./kargo run
   ```

2. **Test the endpoint:**
   The service listens on port **3000** and acts as a proxy for a sample objects API. Run this in your terminal:
   ```bash
   curl http://localhost:3000/
   ```

   You should see a JSON list of objects fetched directly from the remote API! 🌐

## 📂 Project Structure

- `module.yaml`: Defines the product type (`linux/app`), entry point, and mixed dependencies (Git + Maven).
- `src/com/example/demo/main.kt`: The core service logic using Ktor Server to listen for requests and Ktor Client (Native) to fetch data.
- `.gitignore`: Pre-configured to keep your repository clean of build artifacts and IDE folders.
- `kargo` / `kargo.bat`: Handy wrappers to run builds without installing Kargo globally.

## 🧠 How the Magic Works

Take a look at `module.yaml`! It tells Kargo to fetch the native Ktor client connector directly from a Git repository. When you build, Kargo resolves all dependencies, links them into a single standalone binary, and configures the CIO server engine to run natively on Linux. No heavy runtimes, just pure native performance! 🪄🎉
