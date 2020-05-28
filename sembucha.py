#!/usr/bin/python
from pathlib import Path
from shutil import copyfile
import os
import subprocess
import argparse

sembucha_dir = Path(os.environ["HOME"])/Path(".local/share/Sembucha")

def run_client():
    try:
        subprocess.run(["java", "-cp", "sembucha-0.1-jar-with-dependencies.jar", "com.slezevicius.sembucha.App"], shell=False, cwd=sembucha_dir)
    except KeyboardInterrupt:
        pass

def status():
    with open(sembucha_dir/"torrents.properties", "r") as f:
        data = f.read().split("\n")
        if data[-1] == "":
            data = data[:-1]
    for prop in data:
        name, _ = prop.split("=")
        grep = subprocess.Popen(["grep", f"{name} writing", "logs/app.log"], stdout=subprocess.PIPE,
                cwd=sembucha_dir)
        wc = subprocess.check_output(["wc", "-l"], stdin=grep.stdout)
        print(f"{name} {int(wc)}")

def list_torrents():
    with open(sembucha_dir/"torrents.properties", "r") as f:
        data = f.read().strip().split("\n")
    for prop in data:
        name, info = prop.split("=")
        status, download_path = info.strip().split(",")
        print(f"{name} status={status} download={download_path}")

def add_torrents(tors):
    for tor in tors:
        p = sembucha_dir/f"Torrents/{tor}"
        copyfile(tor, p)
    with open(sembucha_dir/"torrents.properties", "r") as f:
        data = f.read().strip().split("\n")
    with open(sembucha_dir/"torrents.properties", "a") as f:
        for tor in tors:
            if tor in data:
                print(f"{tor} is already in the downloads list; ignoring")
                continue
            downloads = Path(os.environ["HOME"])/"Downloads"
            s = f"{tor}=run,{downloads}\n"
            f.write(s)

def remove_torrents(tors):
    with open(sembucha_dir/"torrents.properties", "r") as f:
        data = f.read().strip().split("\n")
    with open(sembucha_dir/"torrents.properties", "w") as f:
        for row in data:
            name, _ = row.split("=")
            if name not in tors:
                f.write(f"{row}\n")
    for tor in tors:
        p = sembucha_dir/f"Torrents/{tor}"
        p.unlink()

def start_torrents(tors):
    with open(sembucha_dir/"torrents.properties", "r") as f:
        data = f.read().strip().split("\n")
    with open(sembucha_dir/"torrents.properties", "w") as f:
        for row in data:
            name, info = row.split("=")
            if name in tors:
                new_info = f"run,{info.split(',')[1]}"
                f.write(f"{name}={new_info}\n")
            else:
                f.write(f"{row}\n")

def stop_torrents(tors):
    with open(sembucha_dir/"torrents.properties", "r") as f:
        data = f.read().strip().split("\n")
    with open(sembucha_dir/"torrents.properties", "w") as f:
        for row in data:
            name, info = row.split("=")
            if name in tors:
                new_info = f"stop,{info.split(',')[1]}"
                f.write(f"{name}={new_info}\n")
            else:
                f.write(f"{row}\n")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-client", help="Start running the client",
            action="store_true")
    parser.add_argument("--status", help="Outputs all the torrents and their status",
            action="store_true")
    parser.add_argument("-l", "--list-torrents", help="Lists all the added torrents and information about them",
            action="store_true")
    parser.add_argument("-a", "--add-torrents", nargs="*", metavar="torrent", action="extend")
    parser.add_argument("-r", "--remove-torrents", nargs="*", metavar="torrent")
    parser.add_argument("-s", "--start-torrents", nargs="*", metavar="torrent")
    parser.add_argument("-p", "--stop-torrents", nargs="*", metavar="torrent")
    args = parser.parse_args()
    arg_count = len([arg_name for arg_name, val in vars(args).items() if isinstance(val, list) or val is True])
    if arg_count == 1:
        if args.run_client:
            run_client()
        elif args.status:
            status()
        elif args.list_torrents:
            list_torrents()
        elif args.add_torrents is not None:
            if len(args.add_torrents) > 0:
                add_torrents(args.add_torrents)
            else:
                print("The number of given torrent files should be more than 0")
        elif args.remove_torrents is not None:
            if len(args.remove_torrents) > 0:
                remove_torrents(args.remove_torrents)
            else:
                print("The number of given torrent files should be more than 0")
        elif args.start_torrents is not None:
            if len(args.start_torrents) > 0:
                start_torrents(args.start_torrents)
            else:
                print("The number of given torrent files should be more than 0")
        elif args.stop_torrents is not None:
            if len(args.stop_torrents) > 0:
                stop_torrents(args.stop_torrents)
            else:
                print("The number of given torrent files should be more than 0")
    else:
        parser.print_help()
