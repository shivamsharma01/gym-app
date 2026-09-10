namespace AccessDemo2s
{
    partial class MultidoorInterlockForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.chb_FirstEnter = new System.Windows.Forms.CheckBox();
            this.label7 = new System.Windows.Forms.Label();
            this.cmb_Door = new System.Windows.Forms.ComboBox();
            this.label1 = new System.Windows.Forms.Label();
            this.groupBox1 = new System.Windows.Forms.GroupBox();
            this.txt_Door8 = new System.Windows.Forms.TextBox();
            this.txt_Door7 = new System.Windows.Forms.TextBox();
            this.txt_Door6 = new System.Windows.Forms.TextBox();
            this.txt_Door5 = new System.Windows.Forms.TextBox();
            this.txt_Door4 = new System.Windows.Forms.TextBox();
            this.txt_Door3 = new System.Windows.Forms.TextBox();
            this.txt_Door2 = new System.Windows.Forms.TextBox();
            this.txt_Door1 = new System.Windows.Forms.TextBox();
            this.label2 = new System.Windows.Forms.Label();
            this.btn_Get = new System.Windows.Forms.Button();
            this.btn_Set = new System.Windows.Forms.Button();
            this.groupBox1.SuspendLayout();
            this.SuspendLayout();
            // 
            // chb_FirstEnter
            // 
            this.chb_FirstEnter.AutoSize = true;
            this.chb_FirstEnter.Location = new System.Drawing.Point(187, 24);
            this.chb_FirstEnter.Name = "chb_FirstEnter";
            this.chb_FirstEnter.Size = new System.Drawing.Size(15, 14);
            this.chb_FirstEnter.TabIndex = 33;
            this.chb_FirstEnter.UseVisualStyleBackColor = true;
            // 
            // label7
            // 
            this.label7.AutoSize = true;
            this.label7.Location = new System.Drawing.Point(81, 24);
            this.label7.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label7.Name = "label7";
            this.label7.Size = new System.Drawing.Size(98, 14);
            this.label7.TabIndex = 32;
            this.label7.Text = "Enable():";
            // 
            // cmb_Door
            // 
            this.cmb_Door.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_Door.FormattingEnabled = true;
            this.cmb_Door.Items.AddRange(new object[] {
            "0",
            "1",
            "2",
            "3",
            "4",
            "5",
            "6",
            "7"});
            this.cmb_Door.Location = new System.Drawing.Point(187, 57);
            this.cmb_Door.Name = "cmb_Door";
            this.cmb_Door.Size = new System.Drawing.Size(118, 22);
            this.cmb_Door.TabIndex = 31;
            this.cmb_Door.SelectedIndexChanged += new System.EventHandler(this.cmb_Door_SelectedIndexChanged);
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(39, 60);
            this.label1.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(140, 14);
            this.label1.TabIndex = 30;
            this.label1.Text = "GroupNo.():";
            // 
            // groupBox1
            // 
            this.groupBox1.Controls.Add(this.txt_Door8);
            this.groupBox1.Controls.Add(this.txt_Door7);
            this.groupBox1.Controls.Add(this.txt_Door6);
            this.groupBox1.Controls.Add(this.txt_Door5);
            this.groupBox1.Controls.Add(this.txt_Door4);
            this.groupBox1.Controls.Add(this.txt_Door3);
            this.groupBox1.Controls.Add(this.txt_Door2);
            this.groupBox1.Controls.Add(this.txt_Door1);
            this.groupBox1.Controls.Add(this.label2);
            this.groupBox1.Location = new System.Drawing.Point(42, 99);
            this.groupBox1.Name = "groupBox1";
            this.groupBox1.Size = new System.Drawing.Size(393, 140);
            this.groupBox1.TabIndex = 34;
            this.groupBox1.TabStop = false;
            this.groupBox1.Text = "GroupInfo()";
            // 
            // txt_Door8
            // 
            this.txt_Door8.Location = new System.Drawing.Point(293, 96);
            this.txt_Door8.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Door8.Name = "txt_Door8";
            this.txt_Door8.Size = new System.Drawing.Size(80, 23);
            this.txt_Door8.TabIndex = 39;
            // 
            // txt_Door7
            // 
            this.txt_Door7.Location = new System.Drawing.Point(203, 96);
            this.txt_Door7.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Door7.Name = "txt_Door7";
            this.txt_Door7.Size = new System.Drawing.Size(80, 23);
            this.txt_Door7.TabIndex = 38;
            // 
            // txt_Door6
            // 
            this.txt_Door6.Location = new System.Drawing.Point(113, 96);
            this.txt_Door6.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Door6.Name = "txt_Door6";
            this.txt_Door6.Size = new System.Drawing.Size(80, 23);
            this.txt_Door6.TabIndex = 37;
            // 
            // txt_Door5
            // 
            this.txt_Door5.Location = new System.Drawing.Point(23, 96);
            this.txt_Door5.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Door5.Name = "txt_Door5";
            this.txt_Door5.Size = new System.Drawing.Size(80, 23);
            this.txt_Door5.TabIndex = 36;
            // 
            // txt_Door4
            // 
            this.txt_Door4.Location = new System.Drawing.Point(293, 63);
            this.txt_Door4.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Door4.Name = "txt_Door4";
            this.txt_Door4.Size = new System.Drawing.Size(80, 23);
            this.txt_Door4.TabIndex = 35;
            // 
            // txt_Door3
            // 
            this.txt_Door3.Location = new System.Drawing.Point(203, 63);
            this.txt_Door3.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Door3.Name = "txt_Door3";
            this.txt_Door3.Size = new System.Drawing.Size(80, 23);
            this.txt_Door3.TabIndex = 34;
            // 
            // txt_Door2
            // 
            this.txt_Door2.Location = new System.Drawing.Point(113, 63);
            this.txt_Door2.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Door2.Name = "txt_Door2";
            this.txt_Door2.Size = new System.Drawing.Size(80, 23);
            this.txt_Door2.TabIndex = 33;
            // 
            // txt_Door1
            // 
            this.txt_Door1.Location = new System.Drawing.Point(23, 63);
            this.txt_Door1.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Door1.Name = "txt_Door1";
            this.txt_Door1.Size = new System.Drawing.Size(80, 23);
            this.txt_Door1.TabIndex = 32;
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(20, 33);
            this.label2.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(133, 14);
            this.label2.TabIndex = 31;
            this.label2.Text = "Door ID():";
            // 
            // btn_Get
            // 
            this.btn_Get.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Get.Location = new System.Drawing.Point(97, 254);
            this.btn_Get.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Get.Name = "btn_Get";
            this.btn_Get.Size = new System.Drawing.Size(100, 22);
            this.btn_Get.TabIndex = 36;
            this.btn_Get.Text = "Get()";
            this.btn_Get.UseVisualStyleBackColor = true;
            this.btn_Get.Click += new System.EventHandler(this.btn_Get_Click);
            // 
            // btn_Set
            // 
            this.btn_Set.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Set.Location = new System.Drawing.Point(274, 254);
            this.btn_Set.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Set.Name = "btn_Set";
            this.btn_Set.Size = new System.Drawing.Size(100, 24);
            this.btn_Set.TabIndex = 35;
            this.btn_Set.Text = "Set()";
            this.btn_Set.UseVisualStyleBackColor = true;
            this.btn_Set.Click += new System.EventHandler(this.btn_Set_Click);
            // 
            // MultidoorInterlockForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(471, 291);
            this.Controls.Add(this.btn_Get);
            this.Controls.Add(this.btn_Set);
            this.Controls.Add(this.groupBox1);
            this.Controls.Add(this.chb_FirstEnter);
            this.Controls.Add(this.label7);
            this.Controls.Add(this.cmb_Door);
            this.Controls.Add(this.label1);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "MultidoorInterlockForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "Multi-door Interlock()";
            this.Load += new System.EventHandler(this.MultidoorInterlockForm_Load);
            this.groupBox1.ResumeLayout(false);
            this.groupBox1.PerformLayout();
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.CheckBox chb_FirstEnter;
        private System.Windows.Forms.Label label7;
        private System.Windows.Forms.ComboBox cmb_Door;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.GroupBox groupBox1;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.TextBox txt_Door4;
        private System.Windows.Forms.TextBox txt_Door3;
        private System.Windows.Forms.TextBox txt_Door2;
        private System.Windows.Forms.TextBox txt_Door1;
        private System.Windows.Forms.Button btn_Get;
        private System.Windows.Forms.Button btn_Set;
        private System.Windows.Forms.TextBox txt_Door8;
        private System.Windows.Forms.TextBox txt_Door7;
        private System.Windows.Forms.TextBox txt_Door6;
        private System.Windows.Forms.TextBox txt_Door5;
    }
}