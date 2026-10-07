#!/usr/bin/env python3
"""Isolated Mosh fixture with a deliberate UDP outage and relay source-port change.
Usage: python3 scripts/mosh_test_server.py --server /path/to/mosh-server -- command ...
Session keys stay in pipes/memory; never printed to the test log or saved to files.
"""
import argparse
import os
from pathlib import Path
import re
import selectors
import signal
import socket
import subprocess
import sys
import tempfile
import time


def relay(front_fd, target):
    front = socket.socket(fileno=front_fd)
    back = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    back.bind(('127.0.0.1', 0))
    select = selectors.DefaultSelector()
    select.register(front, selectors.EVENT_READ, 'front')
    select.register(back, selectors.EVENT_READ, 'back')
    peer = None
    started = None
    rotated = False
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        age = 0 if started is None else time.monotonic() - started
        if age >= 11 and not rotated:
            # The server sees a new UDP source port after the outage.
            select.unregister(back); back.close()
            back = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            back.bind(('127.0.0.1', 0)); select.register(back, selectors.EVENT_READ, 'back')
            rotated = True
        for item, _ in select.select(0.1):
            data, address = item.fileobj.recvfrom(65535)
            if started is None:
                started = time.monotonic()
            age = time.monotonic() - started
            if item.data == 'front':
                peer = address
                if not 4 <= age < 11:
                    back.sendto(data, ('127.0.0.1', target))
            elif peer is not None and not 4 <= age < 11:
                front.sendto(data, peer)
    front.close(); back.close(); select.close()


def bootstrap(server, folder, arguments):
    # Ignore the client-requested port in this fixture only; the relay advertises its own port.
    process = subprocess.run([server, *arguments], capture_output=True)
    match = re.search(rb'(?m)^MOSH CONNECT (\d+) ([A-Za-z0-9+/]{22})\r?$', process.stdout)
    if not match:
        sys.stdout.buffer.write(process.stdout); return process.returncode
    front = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    front.bind(('127.0.0.1', 0))
    child = subprocess.Popen([sys.executable, __file__, 'relay', str(front.fileno()), match[1].decode()],
                             pass_fds=(front.fileno(),), stdin=subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    Path(folder, str(child.pid) + '.pid').write_text(str(child.pid))
    sys.stdout.buffer.write(b'MOSH CONNECT ' + str(front.getsockname()[1]).encode() + b' ' + match[2] + b'\n')
    front.close()
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == 'relay':
        relay(int(sys.argv[2]), int(sys.argv[3])); return 0
    if len(sys.argv) > 1 and sys.argv[1] == 'bootstrap':
        return bootstrap(sys.argv[2], sys.argv[3], sys.argv[4:])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', required=True)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('a test command is required')
    import shlex
    with tempfile.TemporaryDirectory(prefix='shelldeck-mosh-fixture-', dir=os.environ.get('TMPDIR', '/tmp')) as folder:
        wrapper = Path(folder, 'mosh-server')
        wrapper.write_text('#!/bin/sh\nexec ' + shlex.join([sys.executable, str(Path(__file__).resolve()), 'bootstrap', str(Path(args.server).resolve()), folder]) + ' "$@"\n')
        wrapper.chmod(0o700)
        env = os.environ.copy(); env['SSH_TEST_MOSH_BIN'] = str(wrapper); env['MOSH_TEST_ROAMING'] = '1'
        try:
            return subprocess.call([sys.executable, str(Path(__file__).with_name('ssh_test_server.py')), '--', *command], env=env)
        finally:
            for pid in Path(folder).glob('*.pid'):
                try:
                    value = int(pid.read_text())
                    cmdline = Path(f'/proc/{value}/cmdline').read_bytes().split(b'\0')
                    if str(Path(__file__).resolve()).encode() in cmdline and b'relay' in cmdline:
                        os.kill(value, signal.SIGTERM)
                except (ProcessLookupError, FileNotFoundError): pass


if __name__ == '__main__':
    raise SystemExit(main())
