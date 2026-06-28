#!/usr/bin/env python3
import argparse
import os
import socket
import sys
import glob

# Optimal 8 KB network socket chunk chunk read/write size balancing RAM footprint and CPU cycles
CHUNK_SIZE = 8192

def handle_upload(server_ip, server_port, upload_pattern):
    """Expands local wildcards, reports batch size, and streams files sequentially

    with accurate name, size, and system timeline metadata.
    """
    # Scan filesystem for items matching shell path pattern string parameters
    local_files = glob.glob(upload_pattern)
    
    if not local_files:
        print(f"Error: No local files matched the pattern or path: '{upload_pattern}'")
        return

    # Filter targets to isolate flat documents, bypassing folder paths entirely
    file_list = [f for f in local_files if os.path.isfile(f)]
    total_files = len(file_list)
    
    if total_files == 0:
        print("Error: The pattern matches directories but zero actionable files.")
        return

    print(f"Discovered {total_files} matching local files ready for upload pipeline processing.")
    print(f"Connecting to server at {server_ip}:{server_port} for multi-upload...")
    
    # Establish standard internet TCP socket connection endpoint block
    client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        client_socket.connect((server_ip, server_port))
        print("Successfully connected to the Android File Transfer Server!")
        
        # Instantiate persistent binary buffer wrapper to cleanly isolate network reads
        stream_reader = client_socket.makefile('rb')

        # Send action token code "1" to flag an upcoming multi-file upload intention
        request_packet = f"1|multi_upload_mode\n"
        client_socket.sendall(request_packet.encode('utf-8'))

        # Alert the remote device of exactly how many files are pending in this transmission session
        client_socket.sendall(f"{total_files}\n".encode('utf-8'))
        print(f"Sent upload batch confirmation for {total_files} files.")

        for index, file_path in enumerate(file_list):
            # Isolate raw filename from directory paths to ensure Android targets storage correctly
            file_name = os.path.basename(file_path)
            file_size = os.path.getsize(file_path)
            # Fetch computer modification time, stripping microsecond decimals for clean serialization
            epoch_time = int(os.path.getmtime(file_path)) 

            # Package attributes into structured string text header token layout
            metadata_packet = f"{file_name}|{file_size}|{epoch_time}\n"
            client_socket.sendall(metadata_packet.encode('utf-8'))
            print(f"[{index+1}/{total_files}] Uploading: {file_name} ({file_size} bytes)")

            # Block execution, waiting for the Android MediaStore allocation acknowledgment token
            response_bytes = stream_reader.readline()
            server_signal = response_bytes.decode('utf-8').strip() if response_bytes else ""
            if server_signal != "0":
                print(f"[{index}] Server rejected upload handshake block. Aborting batch sequence.")
                break

            # Read raw disk data sequentially and pipe it onto the network interface card
            bytes_sent = 0
            with open(file_path, 'rb') as f:
                while bytes_sent < file_size:
                    chunk = f.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    client_socket.sendall(chunk)
                    bytes_sent += len(chunk)

        print("All file upload batch transmissions completed.")

    except Exception as e:
        print(f"An error occurred during multi-upload operation task: {e}")
    finally:
        # Guarantee socket teardown and file resource cleanup under crash conditions
        client_socket.close()
        print("Socket connection closed safely. Exiting application.")

def handle_download(server_ip, server_port, file_regex):
    """Requests matching files from phone, processes incoming search total structures,

    and sequential handles unredacted text-to-binary packet translation loops.
    """
    print(f"Connecting to server at {server_ip}:{server_port}...")
    client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        client_socket.connect((server_ip, server_port))
        
        # Instantiate persistent buffer session tracker to securely decouple text boundaries from binary blocks
        stream_reader = client_socket.makefile('rb')
        
        # Dispatched action token "0" alongside pattern criteria layout
        request_packet = f"0|{file_regex}\n"
        client_socket.sendall(request_packet.encode('utf-8'))
        
        # Pull layout header string line up until the first delimiter breakpoint
        response_bytes = stream_reader.readline()
        if not response_bytes: return
        
        server_response = response_bytes.decode('utf-8').strip()
        print(f"server_response {server_response}")
        
        # Parse return packet variables: "[Response Code]|[Total Matched File Count]"
        parts = server_response.split('|', 1)
        response_code, num_files_str = parts[0], parts[1] if len(parts) > 1 else ""
        
        if response_code != "0": return
        total_files = int(num_files_str) if num_files_str.strip() else 0
        
        if total_files > 0:
            for index in range(total_files):
                # Alert server we are ready to consume the next sequential file item entry block
                start_signal = "0\n"
                client_socket.sendall(start_signal.encode('utf-8'))
                
                try:
                    # Capture exact metadata token string: "<name>|<size>|<epoch_timestamp>\n"
                    metadata_line = stream_reader.readline()
                    metadata_str = metadata_line.decode('utf-8').strip()
                    
                    m_parts = metadata_str.split('|', 2)
                    f_name, f_size, e_time = m_parts[0], int(m_parts[1]), float(m_parts[2])
                    print(f"[{index+1}/{total_files}] Downloading: {f_name} ({f_size} bytes)")
                    
                    # Deduplication strategy logic checks folder to block data collision overrides
                    out_name = f_name
                    c = 1
                    while os.path.exists(out_name):
                        np = os.path.splitext(f_name)
                        out_name = f"{np[0]}_{c}{np[1]}"
                        c += 1
                        
                    # Strict loop layout ensures we pull *only* the specific size allocated to this file entry window
                    b_rcv = 0
                    with open(out_name, 'wb') as f_out:
                        while b_rcv < f_size:
                            # Bound reading window criteria checks dynamically to prevent spilling into future headers
                            rem = f_size - b_rcv
                            chunk = stream_reader.read(min(CHUNK_SIZE, rem))
                            f_out.write(chunk)
                            b_rcv += len(chunk)
                            
                    # Update local operating system records to copy the original creation timeline structure values
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
    # Setup structural input flags parser engine ruleset
    parser = argparse.ArgumentParser(description="DFileTransfer Client CLI Engine.")
    parser.add_argument('-c', '--ip', type=str, required=True, help="Server IP address")
    
    # Optional parameters fallback configuration profile logic defaults to 9413
    parser.add_argument('-p', '--port', type=int, default=9413, help="Server port number (default: 9413)")
    
    # Enforce strict operation criteria: client cannot stream down and push up at the same instant instance
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('-d', '--download', type=str, help="Download pattern search regex query")
    group.add_argument('-u', '--upload', type=str, help="Local system path targeting file to upload")

    args = parser.parse_args()
    print("--------------------------------------------------")
    print(" DFILETRANSFER CLIENT INITIALIZED                 ")
    print("--------------------------------------------------")
    
    # Direct traffic execution endpoints based on exclusive tracking flags variables parameters
    if args.download:
        handle_download(args.ip, args.port, args.download)
    elif args.upload:
        handle_upload(args.ip, args.port, args.upload)
        
    print("--------------------------------------------------")

if __name__ == "__main__":
    main()
