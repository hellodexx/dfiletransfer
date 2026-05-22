#!/usr/bin/env python3
import argparse
import os
import socket
import sys

CHUNK_SIZE = 8192

import glob

def handle_upload(server_ip, server_port, upload_pattern):
    """Handles expanding local file wildcard paths, reporting total batch counts,

    and streaming multiple binary files sequentially with precise metadata.
    """
    # 1. Use glob to find all matching local files based on your input pattern string
    # (e.g., "photos/*.jpg" or a single explicit name "document.pdf")
    local_files = glob.glob(upload_pattern)
    
    if not local_files:
        print(f"Error: No local files matched the pattern or path: '{upload_pattern}'")
        return

    # Filter out directory folders, keeping only actual file structures
    file_list = [f for f in local_files if os.path.isfile(f)]
    total_files = len(file_list)
    
    if total_files == 0:
        print("Error: The pattern matches directories but zero actionable files.")
        return

    print(f"Discovered {total_files} matching local files ready for upload pipeline processing.")
    print(f"Connecting to server at {server_ip}:{server_port} for multi-upload...")
    
    client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        client_socket.connect((server_ip, server_port))
        print("Successfully connected to the Android File Transfer Server!")
        
        stream_reader = client_socket.makefile('rb')

        # 2. Inform the server we want Upload Mode (code "1")
        request_packet = f"1|multi_upload_mode\n"
        client_socket.sendall(request_packet.encode('utf-8'))

        # 3. Inform the server exactly how many files are coming down the pipe
        client_socket.sendall(f"{total_files}\n".encode('utf-8'))
        print(f"Sent upload batch confirmation for {total_files} files.")

        # 4. Loop through every single file item found matching the criteria
        for index, file_path in enumerate(file_list):
            file_name = os.path.basename(file_path)
            file_size = os.path.getsize(file_path)
            # Fetch local modification timestamp, cast to int to drop decimals
            epoch_time = int(os.path.getmtime(file_path)) 

            # Push the individual metadata tracking header row
            metadata_packet = f"{file_name}|{file_size}|{epoch_time}\n"
            client_socket.sendall(metadata_packet.encode('utf-8'))
            # print(f"[{index+1}/{total_files}] Syncing: '{file_name}' ({file_size} bytes)")
            print(f"[{index+1}/{total_files}] Uploading: {file_name} ({file_size} bytes)")

            # Block and wait for the phone's acknowledgment "0" signal
            response_bytes = stream_reader.readline()
            server_signal = response_bytes.decode('utf-8').strip() if response_bytes else ""
            if server_signal != "0":
                print(f"[{index}] Server rejected upload handshake block. Aborting batch sequence.")
                break

            # Stream the file contents up the socket stream channel
            bytes_sent = 0
            with open(file_path, 'rb') as f:
                while bytes_sent < file_size:
                    chunk = f.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    client_socket.sendall(chunk)
                    bytes_sent += len(chunk)

            # print(f"[{index+1}/{total_files}] Finished streaming payload data.")

        print("All file upload batch transmissions completed.")

    except Exception as e:
        print(f"An error occurred during multi-upload operation task: {e}")
    finally:
        client_socket.close()
        print("Socket connection closed safely. Exiting application.")

def handle_download(server_ip, server_port, file_regex):
    # ... This function remains exactly the same as your working version ...
    print(f"Connecting to server at {server_ip}:{server_port}...")
    client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        client_socket.connect((server_ip, server_port))
        stream_reader = client_socket.makefile('rb')
        request_packet = f"0|{file_regex}\n"
        client_socket.sendall(request_packet.encode('utf-8'))
        response_bytes = stream_reader.readline()
        if not response_bytes: return
        server_response = response_bytes.decode('utf-8').strip()
        print(f"server_response {server_response}")
        parts = server_response.split('|', 1)
        response_code, num_files_str = parts[0], parts[1] if len(parts) > 1 else ""
        if response_code != "0": return
        total_files = int(num_files_str) if num_files_str.strip() else 0
        if total_files > 0:
            for index in range(total_files):
                start_signal = "0\n"
                client_socket.sendall(start_signal.encode('utf-8'))
                
                # Internal handler call
                try:
                    metadata_line = stream_reader.readline()
                    metadata_str = metadata_line.decode('utf-8').strip()
                    m_parts = metadata_str.split('|', 2)
                    f_name, f_size, e_time = m_parts[0], int(m_parts[1]), float(m_parts[2])
                    print(f"[{index+1}/{total_files}] Downloading: {f_name} ({f_size} bytes)")
                    
                    # Handle duplication
                    out_name = f_name
                    c = 1
                    while os.path.exists(out_name):
                        np = os.path.splitext(f_name)
                        out_name = f"{np[0]}_{c}{np[1]}"
                        c += 1
                        
                    b_rcv = 0
                    with open(out_name, 'wb') as f_out:
                        while b_rcv < f_size:
                            rem = f_size - b_rcv
                            chunk = stream_reader.read(min(CHUNK_SIZE, rem))
                            f_out.write(chunk)
                            b_rcv += len(chunk)
                    os.utime(out_name, (e_time, e_time))
                except Exception as loop_err:
                    print(f"Error in download index loop: {loop_err}")
                    break
        else:
            print("No matching files found to download.")
    except Exception as e:
        print(f"Download Error: {e}")
    finally:
        client_socket.close()

def main():
    parser = argparse.ArgumentParser(description="DFileTransfer Client CLI Engine.")
    parser.add_argument('-c', '--ip', type=str, required=True, help="Server IP address")
    parser.add_argument('-p', '--port', type=int, required=True, help="Server port number")
    
    # Mutually exclusive flags group layout configuration processing
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('-d', '--download', type=str, help="Download pattern search regex query")
    group.add_argument('-u', '--upload', type=str, help="Local system path targeting file to upload")

    args = parser.parse_args()
    print("--------------------------------------------------")
    print(" DFILETRANSFER CLIENT INITIALIZED                 ")
    print("--------------------------------------------------")
    
    if args.download:
        handle_download(args.ip, args.port, args.download)
    elif args.upload:
        handle_upload(args.ip, args.port, args.upload)
        
    print("--------------------------------------------------")

if __name__ == "__main__":
    main()
