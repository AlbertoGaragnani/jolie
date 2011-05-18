include "backend.iol"
include "console.iol"
include "string_utils.iol"
include "../common/locations.iol"
include "file.iol"

execution { concurrent }

inputPort JHomeBackendInput {
Location: "local"
Interfaces: JHomeBackendInterface
}

include "../common/jhome_database.iol"

init
{
	global.header = "<html>
<head>
<title>JHome Admin</title>
<link rel=\"stylesheet\" href=\"../../css/960.css\" />
<link rel=\"stylesheet\" href=\"../../css/reset.css\" />
<link rel=\"stylesheet\" href=\"../../css/text.css\" />
<script type=\"text/javascript\" src=\"../../lib/jquery/jquery-1.4.2.js\"></script>
<script type=\"text/javascript\" src=\"../../lib/jhome/jhome.js\"></script>";
//</head>";
	global.footer = "</body></html>";

	install( SQLException => println@Console( main.SQLException.stackTrace )() )
}

main
{
	[ getPageTemplate( pageName )( content ) {
		q = "select P.id as page_id, L.name as layout_name from
pages as P, layouts as L
where
P.name = :name and L.id = P.layout_id";
		q.name = pageName;
		query@Database( q )( result );
		if ( #result.row > 0 ) {
			f.filename = "www/layouts/" + result.row.LAYOUT_NAME + ".html";
			f.format = "text";
			readFile@File( f )( body );
			content = global.header;
			undef( q );
			content += "</head><body>";
			content += body;
			q = "select C.name, W.ID, W.div_name from
widgets as W, widget_classes as C where
W.class_id = C.id and W.page_id = :page_id";
			q.page_id = result.row.PAGE_ID;
			query@Database( q )( result );
			for( i = 0, i < #result.row, i++ ) {
				content += "Widget: " + result.row[i].NAME + " -> Div: " + result.row[i].DIV_NAME + "<br/>"
			};						
			content += global.footer
		} else {
			throw( PageTemplateNotFound )
		}
	} ] { nullProcess }
}