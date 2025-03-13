#!/usr/bin/perl
#
# nokia-suite-convert.pl - convert SMS export from CSV exported
#    by Nokia Suite into CSV that can be parsed by csv-convert.py
#
# Copyright (c) 2023 - 2024 by Matus UHLAR <uhlar@fantomas.sk>
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

use strict;
use Text::CSV;
use Time::Local qw( timelocal_posix );
use POSIX::strptime;
use bignum;
use open qw( :std :encoding(UTF-8) );

my ($fnin, $fhin, $fnout,$fhout);
if ($fnin=shift @ARGV) {
	open $fhin, "<", $fnin or die "$fnin $!";
} else {
	$fhin = *STDIN;
}
if ($fnout=shift @ARGV) {
	open $fhout, ">", $fnout or
		die "$fnout $!";
} else {
	$fhout = *STDOUT;
}

my $csvin = Text::CSV->new ({ binary => 1, auto_diag => 1 });
#my $csvout = Text::CSV->new ({ binary => 1, quote_space => 0, quote_binary => 0 });
# always quote if you want to filter in LibreOffice and compare results
my $csvout = Text::CSV->new ({ binary => 1, always_quote => 1 });
$csvout->say($fhout, ["type","read","address","date","body"]);

my ($type,$read,$addr,$date,$text);
my ($year,$mon,$day,$hour,$min);

while (my $row = $csvin->getline ($fhin)) {
	my %type = map { $_ => 1 } (split /,/, $row->[1]);
	if ($type{'RECEIVED'}) {
		$type=1;
		$addr=$row->[2];
	} elsif ($type{'SENT'}) {
		$type=2;
		$addr=$row->[3];
	} else {
		print STDERR "unknown type ",$row->[1],"\n";
		next;
	}
	if ($type{'READ'}) {
		$read=1;
	} else {
		$read=0;
	}
	($min,$hour,$day,$mon,$year) =
		(POSIX::strptime($row->[5], "%Y.%m.%d %H:%M"))[1,2,3,4,5];
	$date=timelocal_posix(0,$min,$hour,$day,$mon,$year);
	$date*=1000;
	$text=$row->[7];
	$text =~ s"\r\n"\\n"g;
	$csvout->say($fhout,[$type,$read,$addr,$date,$text]);
}
close $fhin;

close $fhout;
